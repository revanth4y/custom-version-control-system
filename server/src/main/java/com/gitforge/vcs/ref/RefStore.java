package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.ObjectId;

import java.util.List;
import java.util.Optional;

/**
 * Mutable named pointers into immutable history.
 *
 * <p>A branch is a name and a commit id — nothing more. Creating one writes
 * roughly forty bytes and copies no commit, tree, or blob, which is precisely
 * why branching is cheap: the history it names is already stored, immutably, and
 * is shared by every branch that can reach it.
 *
 * <p>This layer knows nothing about what a commit <em>is</em>. It stores and
 * retrieves object ids. Whether a target actually exists is a rule imposed above
 * it by {@link BranchService}, which keeps the distinction between "a mutable
 * pointer" and "the immutable thing pointed at" from blurring.
 */
public interface RefStore {

    /**
     * Creates a branch.
     *
     * @throws RefException if the name is invalid or the branch already exists
     */
    void createBranch(String name, ObjectId commit);

    /** The commit a branch points at, or empty if no such branch exists. */
    Optional<ObjectId> getBranch(String name);

    boolean branchExists(String name);

    /** Every branch name, sorted, including nested names such as {@code feature/login}. */
    List<String> listBranches();

    /**
     * Moves an existing branch to another commit.
     *
     * @throws RefException if the branch does not exist
     */
    void updateBranch(String name, ObjectId commit);

    /**
     * Removes a branch. The commit it named, and every object beneath, are left
     * untouched.
     *
     * @throws RefException if the branch does not exist
     */
    void deleteBranch(String name);

    /** What HEAD currently names. */
    Head readHead();

    void setHead(Head head);

    /**
     * The commit HEAD ultimately resolves to.
     *
     * @return empty when HEAD names a branch that does not exist yet, as on a
     *     freshly initialised repository with no commits
     */
    Optional<ObjectId> resolveHead();

    /**
     * Every remote-tracking ref this repository holds.
     *
     * <p>Deliberately not folded into {@link #listBranches()}. A tracking ref
     * records what someone else's branch looked like, and treating the two alike
     * is how a fetch starts appearing as local work.
     *
     * <p>Returns the ids alongside the names because the callers that need this
     * list — advertisement, and garbage collection — need both, and asking for
     * them separately would read the store twice for one answer.
     */
    List<RemoteRef> listRemoteRefs();

    /** Where {@code branch} on {@code remote} stood at the last fetch, if it is tracked. */
    Optional<ObjectId> getRemoteRef(String remote, String branch);

    /**
     * Records where a remote's branch now stands.
     *
     * <p>Creates or replaces; a tracking ref has no "already exists" failure,
     * because a fetch that finds the remote unchanged and a fetch that finds it
     * moved should behave the same way.
     */
    void setRemoteRef(String remote, String branch, ObjectId commit);

    /**
     * Forgets one remote-tracking ref.
     *
     * @return true if a ref was removed, false if there was nothing to remove
     */
    boolean deleteRemoteRef(String remote, String branch);

    /**
     * Forgets every tracking ref for one remote.
     *
     * <p>The objects beneath them are untouched, exactly as branch deletion leaves
     * its commits: this drops references, and reclaiming storage stays a separate
     * thing somebody asks for.
     *
     * @return how many refs were removed
     */
    int deleteRemoteRefs(String remote);
}
