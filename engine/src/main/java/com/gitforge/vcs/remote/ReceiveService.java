package com.gitforge.vcs.remote;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.BranchName;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.repository.RepositoryLock;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Accepting objects, and a branch move, from somewhere else.
 *
 * <p>This is the side that must not be talked into anything. Everything arriving
 * here came from a party this server does not control, so the order is fixed:
 *
 * <pre>
 *   verify each object → write it → prove the closure is complete
 *                      → prove the move is a fast-forward → move the branch
 * </pre>
 *
 * <p><strong>The branch moves last, and only last.</strong> Each object is
 * re-hashed by the engine before it is stored, so a sender cannot introduce
 * content under an id that does not describe it. Then the whole history beneath
 * the proposed tip is walked <em>locally</em>: if anything in it is missing, the
 * push is refused and no reference moves. A branch pointing at a commit whose
 * tree was never sent is the one outcome worth any amount of care to avoid.
 *
 * <p><strong>Fast-forward only.</strong> A push that would drop commits is
 * refused rather than resolved. There is no reflog here — {@code docs/security.md}
 * records that a swept object is gone — so the usual escape from a bad force-push
 * does not exist, and offering one would be offering something this engine cannot
 * honour.
 *
 * <p><strong>On sending in several batches.</strong> Objects may arrive across
 * more than one call, with only the last asking for a branch move. Each call
 * takes the lock separately, so a collection can run between them and remove
 * objects that nothing references yet. That costs the sender a retry and nothing
 * else: the closure check happens inside the same locked call that moves the
 * branch, so a partially-collected push fails as incomplete rather than moving a
 * reference onto history that is no longer whole.
 */
public final class ReceiveService {

    private final ObjectStore objects;
    private final RefStore refs;
    private final CommitGraph graph;
    private final RepositoryLock lock;

    public ReceiveService(ObjectStore objects, RefStore refs, CommitGraph graph, RepositoryLock lock) {
        if (objects == null || refs == null || graph == null || lock == null) {
            throw new IllegalArgumentException(
                    "Receiving needs a store, references, a graph and a lock");
        }
        this.objects = objects;
        this.refs = refs;
        this.graph = graph;
        this.lock = lock;
    }

    /**
     * Stores objects and, if asked, moves a branch onto one of them.
     *
     * @param branch the branch to move, or null to store objects only
     * @param commit where it should point, or null when {@code branch} is
     * @throws RemoteException if an object fails verification, the closure is
     *     incomplete, or the move is not a fast-forward
     */
    public Result receive(List<TransferredObject> incoming, String branch, ObjectId commit) {
        if (incoming == null) {
            throw new RemoteException("A receive must carry an object list, even an empty one");
        }
        if (incoming.size() > TransferLimits.MAX_OBJECTS_PER_BATCH) {
            throw new RemoteException(
                    "A receive may carry at most " + TransferLimits.MAX_OBJECTS_PER_BATCH
                            + " objects, not " + incoming.size());
        }
        if ((branch == null) != (commit == null)) {
            throw new RemoteException("A branch move needs both a branch and a commit");
        }
        if (branch != null) {
            BranchName.validate(branch);
        }

        // Verified before anything is written, and weighed before anything is
        // verified: an oversized batch is refused without doing the work.
        long bytes = 0;
        for (TransferredObject object : incoming) {
            bytes += object.payloadBytes();
            if (bytes > TransferLimits.MAX_BATCH_BYTES) {
                throw new RemoteException(
                        "A receive may carry at most " + TransferLimits.MAX_BATCH_BYTES + " bytes");
            }
        }

        // Shared with other writers, excluded from collection - the same exclusion
        // a commit takes, and for the same reason: everything written below is
        // unreachable until the very last line.
        return lock.shared(() -> apply(incoming, branch, commit));
    }

    private Result apply(List<TransferredObject> incoming, String branch, ObjectId commit) {
        int stored = 0;
        for (TransferredObject object : incoming) {
            VcsObject verified = object.verified();
            if (!objects.contains(verified.id())) {
                objects.write(verified);
                stored++;
            }
        }

        if (branch == null) {
            return new Result(stored, null, null);
        }

        requireCompleteClosure(commit);
        requireFastForward(branch, commit);

        if (refs.branchExists(branch)) {
            refs.updateBranch(branch, commit);
        } else {
            refs.createBranch(branch, commit);
        }
        return new Result(stored, branch, commit);
    }

    /**
     * Walks everything the proposed tip needs, locally, and refuses if any of it
     * is absent.
     *
     * <p>The walk is this repository's own, over what is actually on disk. Asking
     * the sender whether it sent enough would be asking the party with the motive.
     */
    private void requireCompleteClosure(ObjectId commit) {
        Set<ObjectId> seen = new LinkedHashSet<>();
        Deque<ObjectId> pending = new ArrayDeque<>();
        pending.push(commit);

        while (!pending.isEmpty()) {
            ObjectId id = pending.pop();
            if (!seen.add(id)) {
                continue;
            }
            if (seen.size() > TransferLimits.MAX_OBJECTS_PER_TRANSFER) {
                throw new RemoteException(
                        "The pushed history exceeds " + TransferLimits.MAX_OBJECTS_PER_TRANSFER
                                + " objects");
            }

            VcsObject object;
            try {
                object = objects.read(id).orElseThrow(() -> new RemoteException(
                        "The push is incomplete: object " + id + " was never sent, so the branch "
                                + "was not moved"));
            } catch (CorruptObjectException ex) {
                throw new RemoteException("Object " + id + " is damaged; the branch was not moved", ex);
            }

            switch (object) {
                case Commit parent -> {
                    pending.push(parent.tree());
                    parent.parents().forEach(pending::push);
                }
                case Tree tree -> tree.entries().stream().map(TreeEntry::id).forEach(pending::push);

                // Tags are not transferred, so one cannot legitimately be reached
                // from a pushed branch tip: branch history is commits, trees and
                // blobs, and nothing in it names a tag. Reaching one means the
                // sender put it there, and the safe answer to an object that
                // should be impossible is to refuse rather than to walk it.
                case Tag tag -> throw new RemoteException(
                        "Object " + tag.id() + " is a tag; tags are not transferred between "
                                + "repositories, so the branch was not moved");

                case Blob ignored -> {
                }
            }
        }
    }

    /**
     * Refuses a move that is not a fast-forward.
     *
     * <p>A branch that does not exist yet is a fast-forward from nothing, which is
     * how a push creates one. A branch already at the proposed commit is accepted
     * as a no-op rather than refused, because a repeated push should be boring.
     */
    private void requireFastForward(String branch, ObjectId commit) {
        Optional<ObjectId> current = refs.getBranch(branch);
        if (current.isEmpty() || current.get().equals(commit)) {
            return;
        }
        if (!graph.isAncestor(current.get(), commit)) {
            throw new NotFastForwardException(
                    "Refusing to move " + branch + " from " + current.get().toHex() + " to "
                            + commit.toHex() + ": that would drop commits, and this engine keeps no "
                            + "reflog to recover them from");
        }
    }

    /**
     * What a receive did.
     *
     * @param storedObjects objects written, excluding ones already held
     * @param branch the branch moved, or null if none was
     * @param commit where it now points, or null
     */
    public record Result(int storedObjects, String branch, ObjectId commit) {
    }
}
