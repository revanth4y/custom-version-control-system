package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.List;
import java.util.Optional;

/**
 * The rules that govern branches, layered over raw reference storage.
 *
 * <p>{@link RefStore} deliberately accepts any object id, because a reference is
 * a pointer and pointers do not validate their targets. The rules that make a
 * branch meaningful live here: a branch must name a commit that exists, and the
 * branch currently checked out cannot be deleted from under HEAD.
 *
 * <p>Creating a branch writes one small file. No commit, tree, or blob is copied
 * — the history is already stored immutably and is simply shared by every branch
 * that can reach it, which is what makes branching free.
 */
public final class BranchService {

    private final RefStore refStore;
    private final ObjectStore objectStore;

    public BranchService(RefStore refStore, ObjectStore objectStore) {
        if (refStore == null || objectStore == null) {
            throw new IllegalArgumentException("Branch service requires a reference store and an object store");
        }
        this.refStore = refStore;
        this.objectStore = objectStore;
    }

    /**
     * Creates a branch at an existing commit.
     *
     * @throws RefException if the name is invalid, the branch exists, or the
     *     target is not a commit in this repository
     */
    public void createBranch(String name, ObjectId startCommit) {
        requireExistingCommit(startCommit);
        refStore.createBranch(name, startCommit);
    }

    /**
     * Creates a branch at whatever {@code startPoint} resolves to.
     *
     * @param startPoint a branch name, {@code HEAD}, or a full commit id
     */
    public void createBranchFrom(String name, String startPoint) {
        ObjectId commit = resolve(startPoint)
                .orElseThrow(() -> new RefException("Cannot resolve start point: " + startPoint));
        createBranch(name, commit);
    }

    public Optional<ObjectId> getBranch(String name) {
        return refStore.getBranch(name);
    }

    public boolean branchExists(String name) {
        return refStore.branchExists(name);
    }

    public List<String> listBranches() {
        return refStore.listBranches();
    }

    /**
     * Moves a branch to another commit.
     *
     * @throws RefException if the branch is absent or the target is not a commit
     */
    public void updateBranch(String name, ObjectId commit) {
        requireExistingCommit(commit);
        refStore.updateBranch(name, commit);
    }

    /**
     * Deletes a branch.
     *
     * <p>Objects are never removed. If no other branch can reach the deleted
     * branch's commit, that history simply becomes unreferenced: still stored,
     * still readable by id, but no longer named. Nothing is lost, because there
     * is no garbage collector to reclaim it — so refusing the deletion, as some
     * tools do, would protect against nothing here.
     *
     * @throws RefException if the branch does not exist or is checked out
     */
    public void deleteBranch(String name) {
        if (!refStore.branchExists(name)) {
            throw new RefException("Branch does not exist: " + name);
        }
        if (refStore.readHead() instanceof Head.OnBranch onBranch && onBranch.branch().equals(name)) {
            throw new RefException("Cannot delete the checked-out branch: " + name);
        }
        refStore.deleteBranch(name);
    }

    /** The branch HEAD is attached to, or empty when HEAD is detached. */
    public Optional<String> currentBranch() {
        return refStore.readHead() instanceof Head.OnBranch onBranch
                ? Optional.of(onBranch.branch())
                : Optional.empty();
    }

    public Head head() {
        return refStore.readHead();
    }

    /** The commit HEAD resolves to, or empty before the first commit. */
    public Optional<ObjectId> headCommit() {
        return refStore.resolveHead();
    }

    /**
     * Resolves a revision to a commit.
     *
     * <p>Accepts {@code HEAD}, a branch name, or a full 40-character commit id.
     * A branch name takes precedence over an id, since a name that happens to
     * look like a hash is still a name the user created.
     */
    public Optional<ObjectId> resolve(String revision) {
        if (revision == null || revision.isBlank()) {
            return Optional.empty();
        }
        String trimmed = revision.trim();

        if (trimmed.equals("HEAD")) {
            return refStore.resolveHead();
        }
        Optional<ObjectId> branch = tryGetBranch(trimmed);
        if (branch.isPresent()) {
            return branch;
        }
        try {
            ObjectId id = ObjectId.fromHex(trimmed);
            return objectStore.contains(id) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** A malformed name is simply not a branch, rather than an error, when resolving. */
    private Optional<ObjectId> tryGetBranch(String name) {
        try {
            return refStore.getBranch(name);
        } catch (RefException ex) {
            return Optional.empty();
        }
    }

    private void requireExistingCommit(ObjectId commit) {
        if (commit == null) {
            throw new RefException("A branch must point at a commit");
        }
        if (!objectStore.contains(commit)) {
            throw new RefException("No such commit in this repository: " + commit);
        }
        // Confirms the target really is a commit, not a tree or blob that
        // happens to be stored; readCommit raises if it is not.
        objectStore.readCommit(commit);
    }
}
