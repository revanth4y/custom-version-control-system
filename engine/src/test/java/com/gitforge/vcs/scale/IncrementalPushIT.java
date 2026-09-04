package com.gitforge.vcs.scale;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.remote.PushService;
import com.gitforge.vcs.remote.ReceiveService;
import com.gitforge.vcs.remote.Remote;
import com.gitforge.vcs.remote.RemoteException;
import com.gitforge.vcs.remote.RemoteTransport;
import com.gitforge.vcs.remote.TransferredObject;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a push costs, before and after it learned to stop early.
 *
 * <p>Measured at the protocol rather than over HTTP: a real {@link PushService}
 * against a real {@link ReceiveService}, through a transport that counts the
 * requests a client would have had to make. The V2.0.16 figures were taken over
 * a live server - 3,180 objects in 107 requests, and 101 requests to add a single
 * file to that history - and the request counts here are the same quantity
 * measured without the network in the way, so they are comparable and, unlike the
 * live run, reproducible on any machine.
 *
 * <p>Each case reports what the unoptimised path would have needed beside what
 * the optimised one did, both computed in the same run, so neither figure is a
 * remembered number from somewhere else.
 */
class IncrementalPushIT {

    private static final Remote REMOTE =
            new Remote("origin", "https://peer.test/repositories/someone/peer");

    private VcsRepository local;
    private VcsRepository peer;
    private Counting transport;
    private PushService push;

    private void open(Path storage) {
        VcsRepositoryFactory factory = new VcsRepositoryFactory(storage);
        local = factory.initialise(RepositoryId.of("local"), "main");
        peer = factory.initialise(RepositoryId.of("peer"), "main");
        transport = new Counting(peer);
        push = new PushService(local.objects(), local.refs(), transport, local.lock());
    }

    private ObjectId commit(String branch, String file, String content, String message) {
        return local.commits().commit(
                branch,
                List.of(new FileChange.Put(
                        file, content.getBytes(StandardCharsets.UTF_8), FileMode.REGULAR_FILE)),
                ScaleFixtures.AUTHOR,
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
     * What the unoptimised push would have cost in requests, for the same push.
     *
     * <p>It asked about every object in the closure, thirty-two ids at a time,
     * and then sent whatever the peer said it needed, five hundred objects at a
     * time. Both halves are counted, because comparing only the questions would
     * flatter the new path - which asks one extra question, for the
     * advertisement, before it starts.
     */
    private int requestsWithoutTheOptimisation(ObjectId tip, int objectsToSend) {
        int questions = (closureSize(tip) + 31) / 32;
        int sends = Math.max(1, (objectsToSend + 499) / 500);
        return questions + sends;
    }

    private int closureSize(ObjectId tip) {
        java.util.Set<ObjectId> seen = new java.util.LinkedHashSet<>();
        java.util.Deque<ObjectId> pending = new java.util.ArrayDeque<>();
        pending.push(tip);
        while (!pending.isEmpty()) {
            ObjectId id = pending.pop();
            if (!seen.add(id)) {
                continue;
            }
            var object = local.objects().read(id).orElseThrow();
            if (object instanceof com.gitforge.vcs.object.Commit c) {
                pending.push(c.tree());
                c.parents().forEach(pending::push);
            } else if (object instanceof com.gitforge.vcs.object.Tree t) {
                t.entries().stream().map(com.gitforge.vcs.object.TreeEntry::id).forEach(pending::push);
            }
        }
        return seen.size();
    }

    private void row(String label, int requests, int wouldHaveBeen, int objects, long millis) {
        System.out.printf(
                "  %-46s %5d req (was %5d)  %6d objects  %7d ms  peak heap %4d MB%n",
                label, requests, wouldHaveBeen, objects, millis, ScaleFixtures.peakHeapMb());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    @DisplayName("push: first, repeated, incremental, shared, divergent, deep")
    void incrementalPush(@TempDir Path storage) throws IOException {
        System.out.println("\n=== Remote: incremental push ===");
        open(storage);

        // ---------------------------------------------------------- A: first
        ObjectId tip = line("main", "a.txt", 1_000, "main");
        ScaleFixtures.resetPeakHeap();
        long started = System.nanoTime();
        PushService.Result first = push.push(REMOTE, "main", "t");
        long millis = (System.nanoTime() - started) / 1_000_000;
        int firstWouldBe = requestsWithoutTheOptimisation(tip, first.sentObjects());
        row("A first push (peer empty)", transport.requests(), firstWouldBe,
                first.sentObjects(), millis);

        assertThat(first.sentObjects()).isEqualTo(closureSize(tip));
        // There is nothing to skip when the peer holds nothing, so this cannot be
        // cheaper - and it must not be more than the one extra question the
        // advertisement costs.
        assertThat(transport.requests())
                .as("a first push pays one extra request and no more")
                .isEqualTo(firstWouldBe + 1);

        // ------------------------------------------------------- B: repeated
        transport.reset();
        ScaleFixtures.resetPeakHeap();
        started = System.nanoTime();
        PushService.Result again = push.push(REMOTE, "main", "t");
        millis = (System.nanoTime() - started) / 1_000_000;
        row("B identical push again", transport.requests(),
                requestsWithoutTheOptimisation(tip, 0), again.sentObjects(), millis);

        assertThat(again.sentObjects()).isZero();
        assertThat(transport.requests())
                .as("nothing to send, and it should not take a survey to find that out")
                .isLessThanOrEqualTo(4);

        // ---------------------------------------------- C: one new descendant
        ObjectId grown = commit("main", "a.txt", "one more\n", "One more");
        int wouldBe = requestsWithoutTheOptimisation(grown, 0);
        transport.reset();
        ScaleFixtures.resetPeakHeap();
        started = System.nanoTime();
        PushService.Result incremental = push.push(REMOTE, "main", "t");
        millis = (System.nanoTime() - started) / 1_000_000;
        row("C one new commit onto that history", transport.requests(), wouldBe,
                incremental.sentObjects(), millis);

        assertThat(incremental.sentObjects()).as("a commit, a tree and a blob").isEqualTo(3);
        assertThat(transport.requests()).isLessThan(wouldBe);

        // --------------------------------------------- D: shared history
        local.branches().createBranch("feature", grown);
        ObjectId featureTip = line("feature", "f.txt", 5, "feature");
        int sharedWouldBe = requestsWithoutTheOptimisation(featureTip, 0);
        transport.reset();
        ScaleFixtures.resetPeakHeap();
        started = System.nanoTime();
        PushService.Result shared = push.push(REMOTE, "feature", "t");
        millis = (System.nanoTime() - started) / 1_000_000;
        row("D second branch over shared history", transport.requests(), sharedWouldBe,
                shared.sentObjects(), millis);

        assertThat(shared.sentObjects()).isEqualTo(15);
        assertThat(transport.requests()).isLessThan(sharedWouldBe);

        // ------------------------------------------------- E: divergent
        local.branches().createBranch("divergent", local.reader().resolve("main~500").orElseThrow());
        ObjectId divergentTip = line("divergent", "d.txt", 20, "divergent");
        int divergentWouldBe = requestsWithoutTheOptimisation(divergentTip, 0);
        transport.reset();
        ScaleFixtures.resetPeakHeap();
        started = System.nanoTime();
        PushService.Result divergent = push.push(REMOTE, "divergent", "t");
        millis = (System.nanoTime() - started) / 1_000_000;
        row("E divergent branch, 500 commits down", transport.requests(), divergentWouldBe,
                divergent.sentObjects(), millis);

        assertThat(divergent.sentObjects())
                .as("only the divergent part is new")
                .isEqualTo(60);
        // The one case the boundary does not help. It holds the commits the peer
        // advertises, which are branch tips; a branch that forked five hundred
        // commits below one of those tips meets nothing in it, so the walk runs
        // to the root exactly as it did before and the two negotiation requests
        // are pure overhead. Knowing the peer holds the ancestors of its tips,
        // rather than only the tips, needs a real have/want negotiation - more
        // protocol than this change is, and noted rather than half-built.
        assertThat(transport.requests())
                .as("no worse than before, plus the advertisement and the boundary question")
                .isLessThanOrEqualTo(divergentWouldBe + 2);

        // ----------------------------------------------------- F: deep
        ObjectId deep = line("main", "a.txt", 1_000, "deeper");
        int deepWouldBe = requestsWithoutTheOptimisation(deep, 0);
        transport.reset();
        ScaleFixtures.resetPeakHeap();
        started = System.nanoTime();
        PushService.Result deepPush = push.push(REMOTE, "main", "t");
        millis = (System.nanoTime() - started) / 1_000_000;
        row("F 1,000 commits onto a 2,000-commit history", transport.requests(), deepWouldBe,
                deepPush.sentObjects(), millis);

        assertThat(deepPush.sentObjects()).isEqualTo(3_000);
        assertThat(transport.requests()).isLessThan(deepWouldBe);

        // Every reference on the peer resolves to a complete, verified closure.
        for (String branch : peer.refs().listBranches()) {
            ObjectId peerTip = peer.refs().getBranch(branch).orElseThrow();
            java.util.Set<ObjectId> seen = new java.util.LinkedHashSet<>();
            java.util.Deque<ObjectId> pending = new java.util.ArrayDeque<>();
            pending.push(peerTip);
            while (!pending.isEmpty()) {
                ObjectId id = pending.pop();
                if (!seen.add(id)) {
                    continue;
                }
                var object = peer.objects().read(id).orElseThrow(() ->
                        new AssertionError("branch " + branch + " reaches missing object " + id));
                if (object instanceof com.gitforge.vcs.object.Commit c) {
                    pending.push(c.tree());
                    c.parents().forEach(pending::push);
                } else if (object instanceof com.gitforge.vcs.object.Tree t) {
                    t.entries().stream()
                            .map(com.gitforge.vcs.object.TreeEntry::id).forEach(pending::push);
                }
            }
            ScaleFixtures.note("closure under " + branch, seen.size());
        }
    }

    /** A real peer, with the requests a client would have made counted on the way. */
    private static final class Counting implements RemoteTransport {

        private final VcsRepository peer;
        private final AtomicInteger requests = new AtomicInteger();

        Counting(VcsRepository peer) {
            this.peer = peer;
        }

        void reset() {
            requests.set(0);
        }

        int requests() {
            return requests.get();
        }

        @Override
        public List<RemoteBranch> advertise(Remote remote) {
            requests.incrementAndGet();
            List<RemoteBranch> branches = new ArrayList<>();
            for (String name : peer.refs().listBranches()) {
                peer.refs().getBranch(name)
                        .ifPresent(tip -> branches.add(new RemoteBranch(name, tip.toHex())));
            }
            return branches;
        }

        @Override
        public List<String> missing(Remote remote, List<String> ids) {
            requests.incrementAndGet();
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
            requests.incrementAndGet();
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

            requests.incrementAndGet();
            ReceiveService.Result result = peer.receives().receive(
                    incoming, branch, commit == null ? null : ObjectId.fromHex(commit));
            return new ReceiveOutcome(
                    result.storedObjects(),
                    result.branch(),
                    result.commit() == null ? null : result.commit().toHex());
        }
    }
}
