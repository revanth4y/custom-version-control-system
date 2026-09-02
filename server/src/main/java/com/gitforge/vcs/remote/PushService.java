package com.gitforge.vcs.remote;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.BranchName;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.repository.RepositoryLock;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sending one branch of this repository to another.
 *
 * <p>The closure is computed here, from the local graph, because this side is the
 * one that has it. The peer is then asked which of those objects it lacks, so a
 * push carries what is needed rather than everything reachable — for a repository
 * whose history the peer already mostly holds, that is the difference between
 * sending a commit and sending a project.
 *
 * <p><strong>One branch per push.</strong> {@code RefStore} moves a single
 * reference atomically and has no all-or-nothing primitive for several. Rather
 * than build one before anything needs it, a push moves one branch, which makes
 * the existing atomic update exactly sufficient and removes the partially-applied
 * push as a possible outcome.
 *
 * <p><strong>The decision to accept is the peer's.</strong> Nothing here checks
 * whether the move is a fast-forward: this side cannot know the peer's current
 * tip without trusting what it was told, and a check made on the sender's word is
 * not a check. {@link ReceiveService} decides, inside the same lock in which it
 * moves the branch.
 */
public final class PushService {

    private final ObjectStore objects;
    private final RefStore refs;
    private final RemoteTransport transport;
    private final RepositoryLock lock;

    public PushService(
            ObjectStore objects, RefStore refs, RemoteTransport transport, RepositoryLock lock) {

        if (objects == null || refs == null || transport == null || lock == null) {
            throw new IllegalArgumentException(
                    "Pushing needs a store, references, a transport and a lock");
        }
        this.objects = objects;
        this.refs = refs;
        this.transport = transport;
        this.lock = lock;
    }

    /**
     * Sends {@code branch} to {@code remote}.
     *
     * @param token a bearer token the peer will accept. Used for this call and
     *     never written down: storing peer credentials is a separate problem with
     *     its own requirements, and a token in a repository file is a token
     *     nobody remembers is there
     * @throws RemoteException if the branch does not exist here, or the transfer
     *     fails
     * @throws NotFastForwardException if the peer refuses the move
     */
    public Result push(Remote remote, String branch, String token) {
        if (remote == null) {
            throw new RemoteException("A push needs a remote");
        }
        BranchName.validate(branch);

        // The closure is read under the shared lock, so it cannot be computed
        // against a repository a collection is midway through changing.
        Snapshot snapshot = lock.shared(() -> snapshot(branch));

        List<String> wanted = missingOnPeer(remote, snapshot.closure());
        List<TransferredObject> payload = read(wanted);

        return send(remote, token, branch, snapshot.tip(), payload);
    }

    /** The tip and everything beneath it, as it stands right now. */
    private Snapshot snapshot(String branch) {
        ObjectId tip = refs.getBranch(branch).orElseThrow(() ->
                new RemoteException("Branch does not exist: " + branch));

        Set<ObjectId> closure = new LinkedHashSet<>();
        Deque<ObjectId> pending = new ArrayDeque<>();
        pending.push(tip);

        while (!pending.isEmpty()) {
            ObjectId id = pending.pop();
            if (!closure.add(id)) {
                continue;
            }
            if (closure.size() > TransferLimits.MAX_OBJECTS_PER_TRANSFER) {
                throw new RemoteException(
                        "The branch history exceeds " + TransferLimits.MAX_OBJECTS_PER_TRANSFER
                                + " objects");
            }
            VcsObject object = objects.read(id).orElseThrow(() ->
                    new RemoteException("Local object " + id + " is missing; nothing was sent"));

            switch (object) {
                case Commit commit -> {
                    pending.push(commit.tree());
                    commit.parents().forEach(pending::push);
                }
                case Tree tree -> tree.entries().stream().map(TreeEntry::id).forEach(pending::push);
                case Blob ignored -> {
                }
            }
        }
        return new Snapshot(tip, List.copyOf(closure));
    }

    /** Asks the peer, in batches, which ids it does not hold. */
    private List<String> missingOnPeer(Remote remote, List<ObjectId> closure) {
        List<String> missing = new ArrayList<>();
        List<String> ids = closure.stream().map(ObjectId::toHex).toList();

        for (int start = 0; start < ids.size(); start += TransferLimits.MAX_IDS_PER_REQUEST) {
            List<String> batch =
                    ids.subList(start, Math.min(start + TransferLimits.MAX_IDS_PER_REQUEST, ids.size()));
            List<String> answer = transport.missing(remote, batch);
            if (answer == null) {
                throw new RemoteException("The remote did not answer which objects it needs");
            }
            // Only ids that were actually offered. A peer asking for something it
            // was not offered is answering a different question.
            answer.stream().filter(batch::contains).forEach(missing::add);
        }
        return missing;
    }

    private List<TransferredObject> read(List<String> ids) {
        List<TransferredObject> payload = new ArrayList<>(ids.size());
        for (String id : ids) {
            ObjectId objectId = ObjectId.fromHex(id);
            VcsObject object = objects.read(objectId).orElseThrow(() ->
                    new RemoteException("Local object " + id + " is missing; nothing was sent"));
            payload.add(TransferredObject.of(object));
        }
        return payload;
    }

    /**
     * Sends the payload, asking for the branch move only on the final call.
     *
     * <p>Objects sent in an earlier batch are unreferenced on the peer until that
     * last call, so a collection there could remove them in between. That is
     * survivable and deliberately not defended against here: the peer verifies the
     * closure inside the same lock in which it moves the branch, so the outcome is
     * a refused push and a retry rather than a branch pointing at a gap.
     */
    private Result send(
            Remote remote, String token, String branch, ObjectId tip, List<TransferredObject> payload) {

        int stored = 0;
        int batches = Math.max(1, ceilingDivide(payload.size(), TransferLimits.MAX_OBJECTS_PER_BATCH));

        for (int index = 0; index < batches; index++) {
            int start = index * TransferLimits.MAX_OBJECTS_PER_BATCH;
            int end = Math.min(start + TransferLimits.MAX_OBJECTS_PER_BATCH, payload.size());
            List<TransferredObject> batch = start >= end ? List.of() : payload.subList(start, end);

            boolean last = index == batches - 1;
            RemoteTransport.ReceiveOutcome outcome = transport.receive(
                    remote,
                    token,
                    batch,
                    last ? branch : null,
                    last ? tip.toHex() : null);

            stored += outcome.storedObjects();
        }
        return new Result(branch, tip, payload.size(), stored);
    }

    private static int ceilingDivide(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private record Snapshot(ObjectId tip, List<ObjectId> closure) {
    }

    /**
     * What a push did.
     *
     * @param branch the branch moved on the remote
     * @param commit where it now points
     * @param sentObjects objects the remote asked for and were sent
     * @param storedObjects objects the remote reported writing
     */
    public record Result(String branch, ObjectId commit, int sentObjects, int storedObjects) {
    }
}
