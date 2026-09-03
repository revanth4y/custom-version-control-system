package com.gitforge.vcs.remote;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.gc.GarbageCollector;
import com.gitforge.vcs.gc.GcReport;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.ref.RemoteRef;
import com.gitforge.vcs.repository.RepositoryLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fetch and push, between two real repositories, without a socket.
 *
 * <p>The transport is stubbed so the <em>algorithms</em> can be examined: which
 * objects are asked for, in what order, what is verified, and what is refused.
 * Both ends are genuine — real object stores, real ref stores, real hashing — so
 * everything below is about the logic rather than about JSON or HTTP.
 *
 * <p>The socket is proven separately, and only once, by
 * {@code RemoteCrossInstanceIT}. Driving these cases through a real server as
 * well would test the servlet container repeatedly and the transfer logic no more
 * thoroughly.
 */
class RemoteTransferTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture theirs;
    private RepositoryFixture ours;
    private RemoteTransport transport;
    private Remote remote;

    @BeforeEach
    void setUp() {
        theirs = new RepositoryFixture(tempDir.resolve("theirs"), tempDir.resolve("theirs-work"));
        ours = new RepositoryFixture(tempDir.resolve("ours"), tempDir.resolve("ours-work"));
        transport = new DirectTransport(theirs);
        remote = new Remote("origin", "https://peer.test/api/v1/repositories/octocat/demo");
    }

    private FetchService fetches() {
        return new FetchService(ours.objectStore(), ours.refStore(), transport, new RepositoryLock());
    }

    private PushService pushes() {
        return new PushService(ours.objectStore(), ours.refStore(), transport, new RepositoryLock());
    }

    private GarbageCollector collector(RepositoryFixture repository) {
        return new GarbageCollector(
                repository.objectStore(), repository.refStore(),
                repository.workTreeState(), new RepositoryLock());
    }

    /** Two commits on their main. */
    private ObjectId seedTheirHistory() {
        ObjectId first = theirs.commit("Their first", null, files("a.txt", "one\n"));
        ObjectId second = theirs.commit("Their second", first, files("a.txt", "two\n"));
        theirs.branches().createBranch("main", second);
        return second;
    }

    @Nested
    @DisplayName("fetch")
    class Fetch {

        @Test
        void bringsEveryObjectBeneathEveryAdvertisedTip() {
            ObjectId tip = seedTheirHistory();

            FetchService.Result result = fetches().fetch(remote);

            assertThat(result.updatedRefs()).containsExactly("origin/main");
            assertThat(result.receivedObjects()).isPositive();
            assertThat(ours.objectStore().contains(tip)).isTrue();
            assertThat(ours.refStore().getRemoteRef("origin", "main")).contains(tip);

            // Everything they hold, we now hold.
            assertThat(ours.objectStore().listIds())
                    .containsExactlyInAnyOrderElementsOf(theirs.objectStore().listIds());
        }

        @Test
        void doesNotCreateLocalBranches() {
            seedTheirHistory();

            fetches().fetch(remote);

            // A fetch is not a merge. Nothing local moved.
            assertThat(ours.refStore().listBranches()).isEmpty();
        }

        @Test
        void fetchingTwiceTransfersNothingTheSecondTime() {
            seedTheirHistory();

            fetches().fetch(remote);
            FetchService.Result again = fetches().fetch(remote);

            assertThat(again.receivedObjects()).isZero();
            assertThat(again.updatedRefs()).containsExactly("origin/main");
        }

        @Test
        void onlyAsksForObjectsItDoesNotAlreadyHold() {
            ObjectId shared = theirs.commit("Shared", null, files("a.txt", "one\n"));
            theirs.branches().createBranch("main", shared);

            // We already hold the whole history, by coincidence of content.
            ours.commit("Shared", null, files("a.txt", "one\n"));

            DirectTransport recording = (DirectTransport) transport;
            recording.requested.clear();
            FetchService.Result result = fetches().fetch(remote);

            assertThat(result.receivedObjects()).isZero();
            assertThat(recording.requested).isEmpty();
            assertThat(ours.refStore().getRemoteRef("origin", "main")).contains(shared);
        }

        @Test
        void picksUpNewCommitsOnASecondFetch() {
            ObjectId first = seedTheirHistory();
            fetches().fetch(remote);

            ObjectId third = theirs.commit("Their third", first, files("a.txt", "three\n"));
            theirs.branches().updateBranch("main", third);

            FetchService.Result result = fetches().fetch(remote);

            assertThat(result.receivedObjects()).isPositive();
            assertThat(ours.refStore().getRemoteRef("origin", "main")).contains(third);
        }

        @Test
        void tracksSeveralBranchesSeparately() {
            ObjectId tip = seedTheirHistory();
            ObjectId side = theirs.commit("Side", tip, files("b.txt", "side\n"));
            theirs.branches().createBranch("feature/login", side);

            fetches().fetch(remote);

            assertThat(ours.refStore().listRemoteRefs()).extracting(RemoteRef::qualifiedName)
                    .containsExactly("origin/feature/login", "origin/main");
        }

        @Test
        void aBranchNameThisRepositoryCannotHoldIsSkippedRatherThanFailingTheFetch() {
            ObjectId tip = seedTheirHistory();
            DirectTransport rigged = (DirectTransport) transport;
            rigged.extraBranches.add(new RemoteTransport.RemoteBranch("../../escape", tip.toHex()));

            FetchService.Result result = fetches().fetch(remote);

            assertThat(result.skippedBranches()).contains("../../escape");
            assertThat(result.updatedRefs()).containsExactly("origin/main");
            assertThat(ours.refStore().listRemoteRefs()).extracting(RemoteRef::branch)
                    .containsExactly("main");
        }

        @Test
        void anObjectThatDoesNotHashToItsIdIsRefusedAndNothingIsTracked() {
            seedTheirHistory();
            ((DirectTransport) transport).corrupt = true;

            assertThatThrownBy(() -> fetches().fetch(remote))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("hashes to");

            // No tracking ref, because the transfer never completed.
            assertThat(ours.refStore().listRemoteRefs()).isEmpty();
        }

        @Test
        void aRemoteThatWithholdsAnObjectFailsRatherThanTrackingAGap() {
            seedTheirHistory();
            ((DirectTransport) transport).withhold = true;

            assertThatThrownBy(() -> fetches().fetch(remote))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("did not send");

            assertThat(ours.refStore().listRemoteRefs()).isEmpty();
        }

        @Test
        void anEmptyRemoteIsAFetchThatDoesNothing() {
            FetchService.Result result = fetches().fetch(remote);

            assertThat(result.updatedRefs()).isEmpty();
            assertThat(result.receivedObjects()).isZero();
            assertThat(ours.objectStore().count()).isZero();
        }
    }

    @Nested
    @DisplayName("fetched objects and collection")
    class Collection {

        @Test
        void survivesASweepAndIsCollectedOnceTheRemoteIsForgotten() {
            ObjectId tip = seedTheirHistory();
            fetches().fetch(remote);

            GcReport kept = collector(ours).collect();
            assertThat(kept.collected()).isEmpty();
            assertThat(ours.objectStore().contains(tip)).isTrue();

            ours.refStore().deleteRemoteRefs("origin");
            GcReport swept = collector(ours).collect();

            assertThat(swept.collected()).contains(tip);
            assertThat(ours.objectStore().contains(tip)).isFalse();
        }
    }

    @Nested
    @DisplayName("push")
    class Push {

        @Test
        void createsTheBranchOnARemoteThatDoesNotHaveIt() {
            ObjectId tip = ours.commit("Our work", null, files("a.txt", "ours\n"));
            ours.branches().createBranch("main", tip);

            PushService.Result result = pushes().push(remote, "main", "a-token");

            assertThat(result.commit()).isEqualTo(tip);
            assertThat(result.sentObjects()).isPositive();
            assertThat(theirs.branches().getBranch("main")).contains(tip);
            assertThat(theirs.objectStore().contains(tip)).isTrue();
        }

        @Test
        void sendsOnlyWhatTheRemoteLacks() {
            ObjectId first = ours.commit("Shared", null, files("a.txt", "one\n"));
            ours.branches().createBranch("main", first);
            pushes().push(remote, "main", "a-token");

            ObjectId second = ours.commit("New", first, files("a.txt", "two\n"));
            ours.branches().updateBranch("main", second);

            PushService.Result result = pushes().push(remote, "main", "a-token");

            // The shared history is not sent twice.
            assertThat(result.sentObjects()).isLessThan(ours.objectStore().listIds().size());
            assertThat(theirs.branches().getBranch("main")).contains(second);
        }

        @Test
        void fastForwardIsAccepted() {
            ObjectId first = ours.commit("First", null, files("a.txt", "one\n"));
            ours.branches().createBranch("main", first);
            pushes().push(remote, "main", "a-token");

            ObjectId second = ours.commit("Second", first, files("a.txt", "two\n"));
            ours.branches().updateBranch("main", second);

            assertThat(pushes().push(remote, "main", "a-token").commit()).isEqualTo(second);
            assertThat(theirs.branches().getBranch("main")).contains(second);
        }

        @Test
        void nonFastForwardIsRefusedAndTheRemoteBranchDoesNotMove() {
            ObjectId base = ours.commit("Base", null, files("a.txt", "base\n"));
            ours.branches().createBranch("main", base);
            pushes().push(remote, "main", "a-token");

            // They move on; we move somewhere else entirely.
            ObjectId theirNext = theirs.commit("Theirs", base, files("a.txt", "theirs\n"));
            theirs.branches().updateBranch("main", theirNext);

            ObjectId ourNext = ours.commit("Ours", base, files("a.txt", "ours\n"));
            ours.branches().updateBranch("main", ourNext);

            assertThatThrownBy(() -> pushes().push(remote, "main", "a-token"))
                    .isInstanceOf(NotFastForwardException.class)
                    .hasMessageContaining("would drop commits");

            assertThat(theirs.branches().getBranch("main")).contains(theirNext);
        }

        @Test
        void pushingTheSameCommitAgainIsANoOp() {
            ObjectId tip = ours.commit("Only", null, files("a.txt", "one\n"));
            ours.branches().createBranch("main", tip);
            pushes().push(remote, "main", "a-token");

            PushService.Result again = pushes().push(remote, "main", "a-token");

            assertThat(again.commit()).isEqualTo(tip);
            assertThat(again.storedObjects()).isZero();
            assertThat(theirs.branches().getBranch("main")).contains(tip);
        }

        @Test
        void pushingAnAbsentBranchFailsBeforeAnythingIsSent() {
            assertThatThrownBy(() -> pushes().push(remote, "nope", "a-token"))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("Branch does not exist");

            assertThat(theirs.objectStore().count()).isZero();
        }

        @Test
        void pushingWithoutATokenIsRefused() {
            ObjectId tip = ours.commit("Our work", null, files("a.txt", "ours\n"));
            ours.branches().createBranch("main", tip);

            assertThatThrownBy(() -> pushes().push(remote, "main", null))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("token");
        }
    }

    @Nested
    @DisplayName("receiving")
    class Receiving {

        private ReceiveService receiver() {
            return new ReceiveService(
                    ours.objectStore(), ours.refStore(),
                    new CommitGraph(ours.objectStore()), new RepositoryLock());
        }

        @Test
        void anIncompletePushDoesNotMoveTheBranch() {
            ObjectId first = theirs.commit("First", null, files("a.txt", "one\n"));
            ObjectId second = theirs.commit("Second", first, files("a.txt", "two\n"));

            // Only the tip is sent; its parent and trees are withheld.
            List<TransferredObject> partial =
                    List.of(TransferredObject.of(theirs.objectStore().read(second).orElseThrow()));

            assertThatThrownBy(() -> receiver().receive(partial, "main", second))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("incomplete");

            assertThat(ours.refStore().branchExists("main")).isFalse();
        }

        @Test
        void objectsWithoutABranchMoveAreStoredAndNothingElseHappens() {
            ObjectId only = theirs.commit("Only", null, files("a.txt", "one\n"));
            List<TransferredObject> objects = everythingIn(theirs);

            ReceiveService.Result result = receiver().receive(objects, null, null);

            assertThat(result.branch()).isNull();
            assertThat(result.storedObjects()).isEqualTo(objects.size());
            assertThat(ours.objectStore().contains(only)).isTrue();
            assertThat(ours.refStore().listBranches()).isEmpty();
        }

        @Test
        void aBranchWithoutACommitIsRefused() {
            assertThatThrownBy(() -> receiver().receive(List.of(), "main", null))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("both a branch and a commit");
        }

        @Test
        void aBranchNameThatWouldEscapeIsRefused() {
            ObjectId only = theirs.commit("Only", null, files("a.txt", "one\n"));

            assertThatThrownBy(() ->
                    receiver().receive(everythingIn(theirs), "../../escape", only))
                    .isInstanceOf(RuntimeException.class);

            assertThat(ours.refStore().listBranches()).isEmpty();
        }

        @Test
        void anOversizedBatchIsRefusedBeforeAnythingIsStored() {
            List<TransferredObject> tooMany = new ArrayList<>();
            for (int index = 0; index <= TransferLimits.MAX_OBJECTS_PER_BATCH; index++) {
                tooMany.add(new TransferredObject(
                        "0".repeat(40), "blob", Base64.getEncoder().encodeToString(new byte[] {1})));
            }

            assertThatThrownBy(() -> receiver().receive(tooMany, null, null))
                    .isInstanceOf(RemoteException.class)
                    .hasMessageContaining("at most");

            assertThat(ours.objectStore().count()).isZero();
        }

        @Test
        void aDuplicateObjectIsNotStoredTwice() {
            List<TransferredObject> objects = everythingIn(theirs);
            theirs.commit("Only", null, files("a.txt", "one\n"));
            objects = everythingIn(theirs);

            receiver().receive(objects, null, null);
            ReceiveService.Result again = receiver().receive(objects, null, null);

            assertThat(again.storedObjects()).isZero();
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static List<TransferredObject> everythingIn(RepositoryFixture repository) {
        List<TransferredObject> objects = new ArrayList<>();
        for (ObjectId id : repository.objectStore().listIds()) {
            objects.add(TransferredObject.of(repository.objectStore().read(id).orElseThrow()));
        }
        return objects;
    }

    /**
     * A peer that is another repository in this JVM.
     *
     * <p>Answers exactly what the HTTP endpoints answer, so the services under
     * test see the same conversation they would over a socket — including the
     * failures worth rehearsing: a corrupted payload, and a peer that quietly does
     * not send what it was asked for.
     */
    private static final class DirectTransport implements RemoteTransport {

        private final RepositoryFixture peer;
        private final List<String> requested = new ArrayList<>();
        private final List<RemoteBranch> extraBranches = new ArrayList<>();
        private boolean corrupt;
        private boolean withhold;

        private DirectTransport(RepositoryFixture peer) {
            this.peer = peer;
        }

        @Override
        public List<RemoteBranch> advertise(Remote remote) {
            List<RemoteBranch> branches = new ArrayList<>();
            for (String name : peer.refStore().listBranches()) {
                peer.refStore().getBranch(name)
                        .ifPresent(tip -> branches.add(new RemoteBranch(name, tip.toHex())));
            }
            branches.addAll(extraBranches);
            return branches;
        }

        @Override
        public List<String> missing(Remote remote, List<String> ids) {
            return ids.stream()
                    .filter(id -> !peer.objectStore().contains(ObjectId.fromHex(id)))
                    .toList();
        }

        @Override
        public List<TransferredObject> objects(Remote remote, List<String> ids) {
            requested.addAll(ids);
            if (withhold) {
                return List.of();
            }
            List<TransferredObject> answer = new ArrayList<>();
            for (String id : ids) {
                peer.objectStore().read(ObjectId.fromHex(id)).ifPresent(object -> answer.add(
                        corrupt ? tampered(object) : TransferredObject.of(object)));
            }
            return answer;
        }

        /** The same id, different bytes — what a lying or broken peer looks like. */
        private static TransferredObject tampered(VcsObject object) {
            return new TransferredObject(
                    object.id().toHex(),
                    object.type().header(),
                    Base64.getEncoder().encodeToString("tampered".getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public ReceiveOutcome receive(
                Remote remote,
                String token,
                List<TransferredObject> objects,
                String branch,
                String commit) {

            if (token == null || token.isBlank()) {
                throw new RemoteException("Pushing to a remote needs a token it will accept");
            }
            RefStore refs = peer.refStore();
            ReceiveService receiver = new ReceiveService(
                    peer.objectStore(), refs, new CommitGraph(peer.objectStore()), new RepositoryLock());

            ReceiveService.Result result = receiver.receive(
                    objects, branch, commit == null ? null : ObjectId.fromHex(commit));

            return new ReceiveOutcome(
                    result.storedObjects(),
                    result.branch(),
                    result.commit() == null ? null : result.commit().toHex());
        }
    }

    /** Kept out of the way of the nested classes above. */
    @Test
    @DisplayName("a detached HEAD on the peer is not advertised")
    void headIsNotAdvertised() {
        ObjectId tip = seedTheirHistory();
        theirs.refStore().setHead(Head.detachedAt(tip));

        List<RemoteTransport.RemoteBranch> advertised = transport.advertise(remote);

        assertThat(advertised).extracting(RemoteTransport.RemoteBranch::branch)
                .containsExactly("main");
    }
}
