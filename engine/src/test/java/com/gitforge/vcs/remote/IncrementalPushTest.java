package com.gitforge.vcs.remote;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A push that sends less must still leave the peer with everything.
 *
 * <p>Pushing used to enumerate the branch's entire history and ask the peer about
 * all of it, however much of it the peer already had. It now asks as it walks and
 * stops descending wherever the peer says it already holds an object. That sends
 * the same objects in far fewer requests - and it rests on a guess, that a peer
 * holding an object holds what is beneath it, which need not be true.
 *
 * <p>So the guess is never load-bearing. The receiving side walks the proposed
 * tip over its own disk, inside the lock in which it moves the reference, and
 * refuses if anything is absent or damaged. A wrong guess costs a refused push,
 * which the sender answers by sending the whole closure. These tests hold that
 * line: the object set must match a reference closure computed independently, the
 * final state must be identical, and no failure may leave a reference over a gap.
 *
 * <p>The peer here is a real {@link ReceiveService} over a real repository,
 * reached through a transport that counts requests and can be told to fail. A
 * mock peer would prove the sender talks to itself consistently, which is not the
 * question.
 */
class IncrementalPushTest {

    private static final Signature AUTHOR =
            Signature.of("push", "push@localhost", Instant.parse("2026-01-01T00:00:00Z"));

    @TempDir
    Path storage;

    private VcsRepository local;
    private VcsRepository peer;
    private CountingTransport transport;
    private PushService pushService;
    private Remote remote;

    @BeforeEach
    void setUp() {
        VcsRepositoryFactory factory = new VcsRepositoryFactory(storage);
        local = factory.initialise(RepositoryId.of("local"), "main");
        peer = factory.initialise(RepositoryId.of("peer"), "main");
        transport = new CountingTransport(peer);
        pushService = new PushService(local.objects(), local.refs(), transport, local.lock());
        remote = new Remote("origin", "https://peer.test/repositories/someone/peer");
    }

    // ------------------------------------------------------------- fixtures

    private ObjectId commit(String branch, String file, String content, String message) {
        return local.commits().commit(
                branch,
                List.of(new FileChange.Put(
                        file, content.getBytes(StandardCharsets.UTF_8), FileMode.REGULAR_FILE)),
                AUTHOR,
                message);
    }

    private ObjectId line(String branch, String file, int count, String label) {
        ObjectId tip = null;
        for (int i = 0; i < count; i++) {
            tip = commit(branch, file, label + " " + i + "\n", label + " commit " + i);
        }
        return tip;
    }

    /**
     * The closure as the unoptimised push computed it: everything beneath the
     * tip, walked without asking anybody anything.
     *
     * <p>Written out here rather than called through, so the optimisation is
     * compared against a definition instead of against itself.
     */
    private Set<ObjectId> referenceClosure(VcsRepository repository, ObjectId tip) {
        Set<ObjectId> closure = new LinkedHashSet<>();
        Deque<ObjectId> pending = new ArrayDeque<>();
        pending.push(tip);
        while (!pending.isEmpty()) {
            ObjectId id = pending.pop();
            if (!closure.add(id)) {
                continue;
            }
            VcsObject object = repository.objects().read(id).orElseThrow();
            if (object instanceof Commit commit) {
                pending.push(commit.tree());
                commit.parents().forEach(pending::push);
            } else if (object instanceof Tree tree) {
                tree.entries().stream().map(TreeEntry::id).forEach(pending::push);
            }
        }
        return closure;
    }

    /** What the reference algorithm would have sent: the closure minus what the peer holds. */
    private Set<ObjectId> referenceWanted(ObjectId tip) {
        Set<ObjectId> wanted = new LinkedHashSet<>();
        for (ObjectId id : referenceClosure(local, tip)) {
            if (!peer.objects().contains(id)) {
                wanted.add(id);
            }
        }
        return wanted;
    }

    private void assertPeerHoldsCompleteClosure(String branch) {
        ObjectId tip = peer.refs().getBranch(branch).orElseThrow(
                () -> new AssertionError("the peer has no branch " + branch));
        // Reading each object verifies it against its id, so this is a
        // completeness and an integrity check at once.
        Set<ObjectId> onPeer = referenceClosure(peer, tip);
        assertThat(onPeer).isNotEmpty();
        for (ObjectId id : onPeer) {
            assertThat(peer.objects().read(id))
                    .as("object " + id + " reachable from " + branch + " is present and verifies")
                    .isPresent();
        }
        assertThat(onPeer)
                .as("the peer's closure matches what this repository has beneath the same tip")
                .isEqualTo(referenceClosure(local, tip));
    }

    // -------------------------------------------------------- idempotence

    @Nested
    @DisplayName("sends what is needed and no more")
    class ObjectSets {

        @Test
        @DisplayName("a first push sends exactly the reference closure")
        void firstPush() {
            ObjectId tip = line("main", "a.txt", 5, "main");
            Set<ObjectId> expected = referenceWanted(tip);

            PushService.Result result = pushService.push(remote, "main", "token");

            assertThat(result.sentObjects()).isEqualTo(expected.size());
            assertThat(transport.received()).containsExactlyInAnyOrderElementsOf(expected);
            assertPeerHoldsCompleteClosure("main");
        }

        @Test
        @DisplayName("pushing the same tip twice sends nothing the second time")
        void identicalPushTwice() {
            line("main", "a.txt", 6, "main");
            pushService.push(remote, "main", "token");

            transport.resetCounters();
            PushService.Result again = pushService.push(remote, "main", "token");

            assertThat(again.sentObjects()).as("nothing left to send").isZero();
            assertThat(transport.missingCalls())
                    .as("and it took one question to establish that")
                    .isEqualTo(1);
            assertPeerHoldsCompleteClosure("main");
        }

        @Test
        @DisplayName("one new commit sends one commit's worth of objects")
        void oneNewDescendant() {
            line("main", "a.txt", 300, "main");
            pushService.push(remote, "main", "token");

            ObjectId tip = commit("main", "a.txt", "one more\n", "One more");
            Set<ObjectId> expected = referenceWanted(tip);
            transport.resetCounters();

            PushService.Result result = pushService.push(remote, "main", "token");

            assertThat(transport.received()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(result.sentObjects())
                    .as("a commit, its tree and its blob")
                    .isEqualTo(expected.size());

            // Compared against what enumerating the whole closure would have
            // cost, rather than against a number chosen by hand. The walk
            // descends a level per round, so a three-object delta takes three
            // questions however long the history behind it is; asking about the
            // whole closure takes one question per thirty-two objects in it.
            int closure = referenceClosure(local, tip).size();
            int askingAboutEverything =
                    (closure + TransferLimits.MAX_IDS_PER_REQUEST - 1)
                            / TransferLimits.MAX_IDS_PER_REQUEST;
            assertThat(transport.missingCalls())
                    .as("questions asked, against " + askingAboutEverything
                            + " to enumerate all " + closure + " objects")
                    .isLessThan(askingAboutEverything);
            assertPeerHoldsCompleteClosure("main");
        }

        @Test
        @DisplayName("a second branch over shared history sends only its own part")
        void multipleRefsSharingHistory() {
            line("main", "a.txt", 20, "main");
            pushService.push(remote, "main", "token");

            ObjectId forkPoint = local.branches().headCommit().orElseThrow();
            local.branches().createBranch("feature", forkPoint);
            ObjectId featureTip = line("feature", "f.txt", 3, "feature");

            Set<ObjectId> expected = referenceWanted(featureTip);
            transport.resetCounters();
            pushService.push(remote, "feature", "token");

            assertThat(transport.received()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(expected)
                    .as("only the feature commits are new")
                    .hasSizeLessThan(referenceClosure(local, featureTip).size());
            assertPeerHoldsCompleteClosure("feature");
            assertPeerHoldsCompleteClosure("main");
        }

        @Test
        @DisplayName("after a merge, both sides plus the merge commit arrive")
        void afterAMerge() {
            line("main", "a.txt", 5, "main");
            pushService.push(remote, "main", "token");

            ObjectId fork = local.branches().headCommit().orElseThrow();
            local.branches().createBranch("feature", fork);
            line("feature", "f.txt", 3, "feature");
            line("main", "a.txt", 2, "more main");
            local.merges().merge("main", "feature", AUTHOR, AUTHOR, "Merge feature");

            ObjectId tip = local.branches().getBranch("main").orElseThrow();
            Set<ObjectId> expected = referenceWanted(tip);
            transport.resetCounters();
            pushService.push(remote, "main", "token");

            assertThat(transport.received()).containsExactlyInAnyOrderElementsOf(expected);
            assertPeerHoldsCompleteClosure("main");
        }

        @Test
        @DisplayName("an unrelated history sends all of itself")
        void unrelatedHistory() {
            line("main", "a.txt", 4, "main");
            pushService.push(remote, "main", "token");

            local.commits().commit(
                    "orphan",
                    List.of(new FileChange.Put(
                            "o.txt", "orphan\n".getBytes(StandardCharsets.UTF_8), FileMode.REGULAR_FILE)),
                    AUTHOR,
                    "Unrelated root");
            ObjectId orphanTip = line("orphan", "o.txt", 2, "orphan");

            Set<ObjectId> expected = referenceWanted(orphanTip);
            transport.resetCounters();
            pushService.push(remote, "orphan", "token");

            assertThat(transport.received()).containsExactlyInAnyOrderElementsOf(expected);
            assertPeerHoldsCompleteClosure("orphan");
        }

        @Test
        @DisplayName("a branch the peer holds objects for but does not name is still created")
        void objectsPresentButRefAbsent() {
            line("main", "a.txt", 8, "main");
            pushService.push(remote, "main", "token");

            // Same commits, a name the peer has never heard of.
            ObjectId tip = local.branches().getBranch("main").orElseThrow();
            local.branches().createBranch("release", tip);

            transport.resetCounters();
            PushService.Result result = pushService.push(remote, "release", "token");

            assertThat(result.sentObjects()).as("the objects are already there").isZero();
            assertThat(peer.refs().getBranch("release")).contains(tip);
            assertPeerHoldsCompleteClosure("release");
        }
    }

    // ------------------------------------------------- the dangerous case

    @Nested
    @DisplayName("when the peer holds an object without its ancestors")
    class IncompletePeer {

        @Test
        @DisplayName("a push that looks like a no-op still repairs a peer with a hole")
        void fallsBackToTheFullClosure() {
            ObjectId tip = line("main", "a.txt", 8, "main");
            pushService.push(remote, "main", "token");
            assertPeerHoldsCompleteClosure("main");

            // The peer keeps its branch at the tip but loses objects beneath it.
            // It will therefore advertise the tip, confirm holding it, and the
            // boundary will stop the walk immediately - which is precisely the
            // guess this optimisation makes and cannot verify.
            ObjectId first = local.reader().resolve("main~7").orElseThrow();
            ObjectId second = local.reader().resolve("main~6").orElseThrow();
            assertThat(peer.objects().delete(first)).isTrue();
            assertThat(peer.objects().delete(second)).isTrue();

            transport.resetCounters();
            // Nothing new to push: the same tip, already named by the peer.
            PushService.Result result = pushService.push(remote, "main", "token");

            assertThat(transport.refusedReceives())
                    .as("the peer refused the short attempt, which is it doing its job")
                    .isPositive();
            assertThat(result.sentObjects())
                    .as("the fallback sent what was actually missing")
                    .isPositive();
            assertThat(peer.refs().getBranch("main")).contains(tip);
            assertPeerHoldsCompleteClosure("main");
        }

        @Test
        @DisplayName("a hole deeper in the history is filled the same way")
        void aHoleBeneathAPushedTip() {
            line("main", "a.txt", 10, "main");
            pushService.push(remote, "main", "token");

            // Remove one object from the middle of the peer's history, leaving
            // its descendants in place. The peer will report holding everything
            // except that one, and refuse the move until it arrives.
            ObjectId middle = local.reader().resolve("main~5").orElseThrow();
            assertThat(peer.objects().delete(middle)).isTrue();

            ObjectId tip = commit("main", "a.txt", "after the hole\n", "After the hole");
            transport.resetCounters();
            pushService.push(remote, "main", "token");

            assertThat(peer.refs().getBranch("main")).contains(tip);
            assertPeerHoldsCompleteClosure("main");
        }
    }

    // ------------------------------------------------ failure and retry

    @Nested
    @DisplayName("failures leave nothing half-done")
    class Failures {

        @Test
        @DisplayName("an interrupted transfer moves no reference, and a retry succeeds")
        void interruptedTransfer() {
            line("main", "a.txt", 4, "main");
            pushService.push(remote, "main", "token");
            ObjectId before = peer.refs().getBranch("main").orElseThrow();

            line("main", "a.txt", 3, "more");
            // Every attempt fails, including the fallback. A single failure is
            // deliberately not enough: the sender answers one refusal by sending
            // the whole closure, so failing once would be recovered from rather
            // than observed, and this case is about what an interruption leaves
            // behind when it genuinely does not complete.
            transport.failNextReceives(99);

            assertThatThrownBy(() -> pushService.push(remote, "main", "token"))
                    .isInstanceOf(RemoteException.class);

            assertThat(peer.refs().getBranch("main"))
                    .as("the reference did not move")
                    .contains(before);
            assertPeerHoldsCompleteClosure("main");

            // And the repository is not poisoned: once the transport recovers,
            // the same push works.
            transport.stopFailing();
            transport.resetCounters();
            PushService.Result retry = pushService.push(remote, "main", "token");
            assertThat(retry.commit()).isEqualTo(local.branches().getBranch("main").orElseThrow());
            assertPeerHoldsCompleteClosure("main");
        }

        @Test
        @DisplayName("a peer that answers nothing is refused, not guessed at")
        void malformedMissingResponse() {
            line("main", "a.txt", 3, "main");
            transport.answerMissingWithNull();

            assertThatThrownBy(() -> pushService.push(remote, "main", "token"))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("did not answer");

            assertThat(peer.refs().getBranch("main")).isEmpty();
        }

        @Test
        @DisplayName("a local object that has gone missing stops the push before anything is sent")
        void missingLocalObject() {
            ObjectId tip = line("main", "a.txt", 4, "main");
            ObjectId tree = local.objects().readCommit(tip).tree();
            assertThat(local.objects().delete(tree)).isTrue();

            assertThatThrownBy(() -> pushService.push(remote, "main", "token"))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("is missing");

            assertThat(peer.refs().getBranch("main")).isEmpty();
        }

        @Test
        @DisplayName("a non-fast-forward is refused without a second attempt")
        void nonFastForwardIsNotRetried() {
            line("main", "a.txt", 5, "main");
            pushService.push(remote, "main", "token");

            // The peer moves on independently, so this repository is behind.
            peer.commits().commit(
                    "main",
                    List.of(new FileChange.Put(
                            "p.txt", "peer only\n".getBytes(StandardCharsets.UTF_8),
                            FileMode.REGULAR_FILE)),
                    AUTHOR,
                    "Peer commit");
            ObjectId peerTip = peer.refs().getBranch("main").orElseThrow();

            commit("main", "a.txt", "diverged\n", "Diverged");
            transport.resetCounters();

            assertThatThrownBy(() -> pushService.push(remote, "main", "token"))
                    .isInstanceOf(NotFastForwardException.class);

            assertThat(transport.receiveCalls())
                    .as("refused once, not retried with the whole closure")
                    .isEqualTo(1);
            assertThat(peer.refs().getBranch("main"))
                    .as("the peer kept its own history")
                    .contains(peerTip);
        }
    }

    // ------------------------------------------------------- concurrency

    @Nested
    @DisplayName("under concurrency")
    class Concurrency {

        @Test
        @DisplayName("concurrent pushes of independent branches all arrive complete")
        void independentBranches() throws Exception {
            line("main", "a.txt", 10, "main");
            pushService.push(remote, "main", "token");
            ObjectId fork = local.branches().headCommit().orElseThrow();

            int branches = 6;
            for (int i = 0; i < branches; i++) {
                local.branches().createBranch("b" + i, fork);
                line("b" + i, "b" + i + ".txt", 2, "branch " + i);
            }

            List<Throwable> failures = new CopyOnWriteArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(branches);
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < branches; i++) {
                final String name = "b" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        pushService.push(remote, name, "token");
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).isTrue();

            assertThat(failures).isEmpty();
            for (int i = 0; i < branches; i++) {
                assertPeerHoldsCompleteClosure("b" + i);
            }
        }

        @Test
        @DisplayName("a push racing collection on the peer still leaves a complete closure")
        void pushAgainstCollection() throws Exception {
            line("main", "a.txt", 15, "main");
            pushService.push(remote, "main", "token");

            List<Throwable> failures = new CopyOnWriteArrayList<>();
            AtomicInteger sweeps = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);

            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        peer.gc().collect();
                        sweeps.incrementAndGet();
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        commit("main", "a.txt", "round " + i + "\n", "Round " + i);
                        pushService.push(remote, "main", "token");
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(3, TimeUnit.MINUTES)).isTrue();

            assertThat(failures).isEmpty();
            assertThat(sweeps.get()).isEqualTo(10);
            assertThat(peer.refs().getBranch("main"))
                    .contains(local.branches().getBranch("main").orElseThrow());
            assertPeerHoldsCompleteClosure("main");
        }
    }

    // ---------------------------------------------------------- transport

    /**
     * A real peer behind a transport that can count and can be told to fail.
     *
     * <p>Deliberately not a stub of the receiving logic. Every push here goes
     * through {@link ReceiveService} against a real repository, so the closure
     * check that makes the optimisation safe is the one actually running.
     */
    private static final class CountingTransport implements RemoteTransport {

        private final VcsRepository peer;
        private final List<ObjectId> received = new CopyOnWriteArrayList<>();
        private int missingCalls;
        private int receiveCalls;
        private int refusedReceives;
        private int failReceives;
        private boolean missingReturnsNull;

        CountingTransport(VcsRepository peer) {
            this.peer = peer;
        }

        void resetCounters() {
            received.clear();
            missingCalls = 0;
            receiveCalls = 0;
            refusedReceives = 0;
        }

        void failNextReceives(int count) {
            failReceives = count;
        }

        void stopFailing() {
            failReceives = 0;
        }

        void answerMissingWithNull() {
            missingReturnsNull = true;
        }

        List<ObjectId> received() {
            return List.copyOf(received);
        }

        int missingCalls() {
            return missingCalls;
        }

        int receiveCalls() {
            return receiveCalls;
        }

        int refusedReceives() {
            return refusedReceives;
        }

        @Override
        public List<RemoteBranch> advertise(Remote remote) {
            List<RemoteBranch> branches = new ArrayList<>();
            for (String name : peer.refs().listBranches()) {
                peer.refs().getBranch(name)
                        .ifPresent(tip -> branches.add(new RemoteBranch(name, tip.toHex())));
            }
            return branches;
        }

        @Override
        public List<String> missing(Remote remote, List<String> ids) {
            missingCalls++;
            if (missingReturnsNull) {
                return null;
            }
            List<String> absent = new ArrayList<>();
            for (String id : ids) {
                if (!peer.objects().contains(ObjectId.fromHex(id))) {
                    absent.add(id);
                }
            }
            return absent;
        }

        @Override
        public List<TransferredObject> objects(Remote remote, List<String> ids) {
            List<TransferredObject> payload = new ArrayList<>();
            for (String id : ids) {
                peer.objects().read(ObjectId.fromHex(id))
                        .ifPresent(object -> payload.add(TransferredObject.of(object)));
            }
            return payload;
        }

        @Override
        public ReceiveOutcome receive(
                Remote remote, String token, List<TransferredObject> incoming,
                String branch, String commit) {

            receiveCalls++;
            if (failReceives > 0) {
                failReceives--;
                throw new RemoteException("The transfer was interrupted");
            }
            incoming.forEach(object -> received.add(object.verified().id()));
            try {
                ReceiveService.Result result = peer.receives().receive(
                        incoming, branch, commit == null ? null : ObjectId.fromHex(commit));
                return new ReceiveOutcome(
                        result.storedObjects(),
                        result.branch(),
                        result.commit() == null ? null : result.commit().toHex());
            } catch (NotFastForwardException refused) {
                refusedReceives++;
                throw refused;
            } catch (RemoteException refused) {
                refusedReceives++;
                throw refused;
            }
        }
    }
}
