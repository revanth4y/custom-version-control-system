package com.gitforge.vcs.repository;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.tree.PathUpdate;
import com.gitforge.vcs.tree.TreeUpdater;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording changes as commits.
 *
 * <p>Multi-file commits are the primary write: a commit is a snapshot, so
 * changing several files at once is the normal case and changing one is merely
 * the smallest version of it.
 *
 * <pre>
 *   branch tip (absent for the first commit)
 *       ↓ parent commit → base root tree (empty tree when there is no parent)
 *       ↓ write blobs for every Put
 *       ↓ TreeUpdater — rebuild only the directories the paths touch
 *       ↓ new root tree
 *       ↓ Commit → object store
 *       ↓ branch reference                        ← atomic, and always last
 * </pre>
 *
 * <p>Ordering is the whole of the failure-safety story: the branch is moved only
 * after the tree and the commit are durably stored, and moving it is itself
 * atomic. A failure at any earlier point leaves the branch exactly where it was,
 * with some unreferenced objects in the store and nothing else disturbed.
 */
public final class CommitService {

    private static final ObjectId EMPTY_TREE_ID = Tree.empty().id();

    private final ObjectStore objects;
    private final RefStore refs;
    private final BranchService branches;
    private final TreeUpdater updater;
    private final RepositoryLock lock;

    CommitService(ObjectStore objects, RefStore refs, BranchService branches) {
        this(objects, refs, branches, new RepositoryLock());
    }

    CommitService(
            ObjectStore objects, RefStore refs, BranchService branches, RepositoryLock lock) {
        this.objects = objects;
        this.refs = refs;
        this.branches = branches;
        this.updater = new TreeUpdater(objects);
        this.lock = lock;
    }

    /**
     * Commits a set of file changes to a branch.
     *
     * <p>If the branch does not exist it is created, which is how the first
     * commit brings the default branch into being.
     *
     * @return the id of the new commit
     * @throws IllegalArgumentException if the changes are empty, or leave the
     *     repository exactly as it was
     */
    public ObjectId commit(String branch, List<FileChange> changes, Signature author, String message) {
        return commit(branch, changes, author, author, message);
    }

    public ObjectId commit(
            String branch,
            List<FileChange> changes,
            Signature author,
            Signature committer,
            String message) {

        // One mutation at a time, excluded from collection.
        //
        // Serialised because the sequence below reads the branch tip to use as
        // the parent and only moves the reference at its end. Two commits doing
        // that concurrently both descend from the same tip, and whichever writes
        // second replaces the first, whose caller had already been handed an id
        // and told it succeeded. Holding the lock across the whole sequence,
        // rather than only its final write, is what closes that window.
        //
        // Excluded from collection because everything written below is
        // unreachable from any branch until that last line, so a sweep running
        // inside it would see live work as garbage.
        return lock.mutating(() -> write(branch, changes, author, committer, message));
    }

    private ObjectId write(
            String branch,
            List<FileChange> changes,
            Signature author,
            Signature committer,
            String message) {

        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("A branch is required");
        }
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("A commit must contain at least one change");
        }

        java.util.Optional<ObjectId> parent = branches.getBranch(branch);
        ObjectId baseTree = parent.map(id -> objects.readCommit(id).tree()).orElse(EMPTY_TREE_ID);

        ObjectId newTree = updater.apply(baseTree, toUpdates(changes));

        // Content addressing makes this check exact: an identical root tree means
        // the repository state is byte-for-byte what it already was.
        if (newTree.equals(baseTree)) {
            throw new IllegalArgumentException("Nothing to commit: the changes leave the repository unchanged");
        }

        Commit commit = new Commit(
                newTree,
                parent.map(List::of).orElseGet(List::of),
                author,
                committer,
                message);
        ObjectId commitId = objects.write(commit);

        // Only now, with everything reachable already persisted, does the branch
        // move — and updateBranch/createBranch is itself an atomic replace.
        if (parent.isPresent()) {
            refs.updateBranch(branch, commitId);
        } else {
            refs.createBranch(branch, commitId);
        }
        return commitId;
    }

    /** Writes the blobs a change set needs and restates it in terms of object ids. */
    private List<PathUpdate> toUpdates(List<FileChange> changes) {
        List<PathUpdate> updates = new ArrayList<>(changes.size());
        for (FileChange change : changes) {
            switch (change) {
                case FileChange.Put put -> {
                    ObjectId blob = objects.write(new Blob(put.content()));
                    updates.add(PathUpdate.put(put.path(), put.mode(), blob));
                }
                case FileChange.Delete delete -> updates.add(PathUpdate.remove(delete.path()));
            }
        }
        return updates;
    }
}
