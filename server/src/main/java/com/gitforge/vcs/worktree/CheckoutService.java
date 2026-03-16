package com.gitforge.vcs.worktree;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Switching what the working tree reflects.
 *
 * <p>The full resolution chain, each link handled by the layer that owns it:
 *
 * <pre>
 *   HEAD ─▶ branch ─▶ commit ─▶ root tree ─▶ recursive walk ─▶ files
 * </pre>
 *
 * <p>Nothing is written until the working tree has been checked, so a refused
 * checkout leaves the repository exactly as it was — HEAD included.
 */
public final class CheckoutService {

    private final RefStore refStore;
    private final BranchService branchService;
    private final ObjectStore objectStore;
    private final WorkingTree workingTree;
    private final WorkTreeState workTreeState;

    public CheckoutService(
            RefStore refStore,
            BranchService branchService,
            ObjectStore objectStore,
            WorkingTree workingTree,
            WorkTreeState workTreeState) {

        if (refStore == null || branchService == null || objectStore == null
                || workingTree == null || workTreeState == null) {
            throw new IllegalArgumentException(
                    "Checkout requires refs, branches, objects, a working tree and its state");
        }
        this.refStore = refStore;
        this.branchService = branchService;
        this.objectStore = objectStore;
        this.workingTree = workingTree;
        this.workTreeState = workTreeState;
    }

    /**
     * Checks out a branch, attaching HEAD to it.
     *
     * @throws RefException if the branch does not exist
     * @throws CheckoutBlockedException if local work would be destroyed
     */
    public void checkoutBranch(String name) {
        ObjectId commit = branchService.getBranch(name)
                .orElseThrow(() -> new RefException("Branch does not exist: " + name));

        applyCommit(commit);
        // HEAD is moved only after the files are in place, so an interrupted
        // materialization cannot leave HEAD claiming a state that was never
        // written.
        refStore.setHead(Head.onBranch(name));
    }

    /**
     * Checks out a commit directly, detaching HEAD.
     *
     * <p>Useful for inspecting a historical revision without inventing a branch
     * for it. Committing from a detached HEAD is a later concern; here it only
     * changes what HEAD records.
     */
    public void checkoutCommit(ObjectId commit) {
        if (commit == null) {
            throw new RefException("A commit is required");
        }
        objectStore.readCommit(commit);

        applyCommit(commit);
        refStore.setHead(Head.detachedAt(commit));
    }

    /** How the files on disk differ from the tree they were last materialized from. */
    public WorkingTreeStatus status() {
        return workingTree.status(currentTreeId());
    }

    /**
     * Materializes a commit's tree after confirming nothing would be lost.
     *
     * <p>Three ways a checkout can destroy work, all refused:
     *
     * <ul>
     *   <li>a tracked file edited locally — overwriting it would discard the edit;
     *   <li>a tracked file deleted locally — restoring it would discard the deletion;
     *   <li>an untracked file sitting where an incoming file must be written.
     * </ul>
     *
     * <p>Untracked files that do not collide with the target are left untouched,
     * because they are not ours to remove.
     */
    private void applyCommit(ObjectId commit) {
        ObjectId targetTree = objectStore.readCommit(commit).tree();
        ObjectId currentTree = currentTreeId();

        WorkingTreeStatus status = workingTree.status(currentTree);
        List<String> blockingUntracked = collidingUntrackedFiles(status, targetTree);

        if (status.hasLocalChanges() || !blockingUntracked.isEmpty()) {
            throw new CheckoutBlockedException(
                    describe(status, blockingUntracked),
                    new WorkingTreeStatus(status.modified(), status.deleted(), blockingUntracked));
        }

        workingTree.materialize(targetTree, currentTree);
        // Recorded only once the files are actually on disk, so an interrupted
        // materialization leaves the previous baseline rather than claiming a
        // state that was never written.
        workTreeState.record(targetTree);
    }

    /** Untracked files that the incoming tree would have to write over. */
    private List<String> collidingUntrackedFiles(WorkingTreeStatus status, ObjectId targetTree) {
        Map<String, ObjectId> incoming = workingTree.trackedFiles(targetTree);

        List<String> colliding = new ArrayList<>();
        for (String path : status.untracked()) {
            if (incoming.containsKey(path)) {
                colliding.add(path);
            }
        }
        return colliding;
    }

    /**
     * The tree the working directory currently reflects.
     *
     * <p>Taken from the recorded materialization, not from HEAD: HEAD may name a
     * branch whose files have never been written, and reading that as the
     * baseline would report an untouched directory as wholesale deletion.
     *
     * <p>Before any checkout the baseline is the empty tree, which says exactly
     * the right thing — nothing is tracked yet — with no special case downstream.
     */
    private ObjectId currentTreeId() {
        return workTreeState.materializedTree()
                .orElseGet(() -> objectStore.write(Tree.empty()));
    }

    private static String describe(WorkingTreeStatus status, List<String> blockingUntracked) {
        StringBuilder message = new StringBuilder("Checkout would overwrite local changes:");
        appendPaths(message, "modified", status.modified());
        appendPaths(message, "deleted", status.deleted());
        appendPaths(message, "untracked", blockingUntracked);
        return message.toString();
    }

    private static void appendPaths(StringBuilder message, String label, List<String> paths) {
        if (!paths.isEmpty()) {
            message.append(' ').append(label).append(' ').append(paths).append(';');
        }
    }
}
