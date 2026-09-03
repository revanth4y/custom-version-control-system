package com.gitforge.vcs.repository;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.merge.MergeResult;
import com.gitforge.vcs.merge.ThreeWayMerger;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.List;

/**
 * Merging one branch into another.
 *
 * <p>Sits between the commit graph, which knows how branches relate, and the
 * tree merger, which knows how to reconcile content. Its own job is deciding
 * which of those is needed and recording the result:
 *
 * <pre>
 *   ours == theirs              → already up to date
 *   theirs is an ancestor of ours → already up to date   (we contain them)
 *   ours is an ancestor of theirs → fast-forward         (no merge commit)
 *   otherwise                     → three-way merge
 * </pre>
 *
 * <p>Equality is tested before the ancestor checks because ancestry is
 * reflexive: identical branches would otherwise be reported as up to date for
 * the wrong reason.
 */
public final class MergeOrchestrator {

    private static final ObjectId EMPTY_TREE_ID = Tree.empty().id();

    private final ObjectStore objects;
    private final RefStore refs;
    private final BranchService branches;
    private final CommitGraph graph;
    private final ThreeWayMerger merger;
    private final RepositoryLock lock;

    MergeOrchestrator(ObjectStore objects, RefStore refs, BranchService branches, CommitGraph graph) {
        this(objects, refs, branches, graph, new RepositoryLock());
    }

    MergeOrchestrator(
            ObjectStore objects,
            RefStore refs,
            BranchService branches,
            CommitGraph graph,
            RepositoryLock lock) {
        this.objects = objects;
        this.refs = refs;
        this.branches = branches;
        this.graph = graph;
        this.merger = new ThreeWayMerger(objects);
        this.lock = lock;
    }

    /**
     * Merges {@code theirBranch} into {@code ourBranch}.
     *
     * <p>Only {@code ourBranch} can move; the branch being merged in is never
     * touched.
     *
     * @throws RefException if either branch does not exist
     */
    public MergeOutcome merge(String ourBranch, String theirBranch, Signature author, String message) {
        return merge(ourBranch, theirBranch, author, author, message);
    }

    public MergeOutcome merge(
            String ourBranch,
            String theirBranch,
            Signature author,
            Signature committer,
            String message) {

        // A merge writes blobs, trees and a commit before it moves the branch,
        // exactly as an ordinary commit does, so it needs the same exclusion from
        // collection for the same reason.
        return lock.shared(() -> apply(ourBranch, theirBranch, author, committer, message));
    }

    private MergeOutcome apply(
            String ourBranch,
            String theirBranch,
            Signature author,
            Signature committer,
            String message) {

        ObjectId ours = requireBranch(ourBranch);
        ObjectId theirs = requireBranch(theirBranch);

        if (ours.equals(theirs)) {
            return new MergeOutcome.AlreadyUpToDate(ours);
        }
        if (graph.isAncestor(theirs, ours)) {
            // Their history is already contained in ours; merging would add
            // nothing.
            return new MergeOutcome.AlreadyUpToDate(ours);
        }
        if (graph.isAncestor(ours, theirs)) {
            // Ours is strictly behind, so the branch only has to catch up. A
            // merge commit here would record a reconciliation that never
            // happened.
            refs.updateBranch(ourBranch, theirs);
            return new MergeOutcome.FastForwarded(theirs);
        }

        return threeWayMerge(ourBranch, theirBranch, ours, theirs, author, committer, message);
    }

    private MergeOutcome threeWayMerge(
            String ourBranch,
            String theirBranch,
            ObjectId ours,
            ObjectId theirs,
            Signature author,
            Signature committer,
            String message) {

        ObjectId baseTree = baseTreeOf(ours, theirs);
        MergeResult result = merger.merge(baseTree, treeOf(ours), treeOf(theirs));

        if (result instanceof MergeResult.Conflicted conflicted) {
            // Nothing has been written and no reference has moved: the tree
            // merger persists nothing when it cannot resolve, and the branch
            // update below was never reached.
            return new MergeOutcome.Conflicted(conflicted.conflicts(), conflicted.cleanlyMerged());
        }

        ObjectId mergedTree = ((MergeResult.Clean) result).tree();

        // Parent order is identity, not presentation: parent 0 is the branch
        // being merged into, parent 1 the branch merged in. Sorting these would
        // silently produce a different commit that claims a different history.
        Commit mergeCommit = new Commit(
                mergedTree,
                List.of(ours, theirs),
                author,
                committer,
                message == null || message.isBlank() ? defaultMessage(ourBranch, theirBranch) : message);

        ObjectId commitId = objects.write(mergeCommit);

        // Last, and atomic. Everything the branch will point at is already
        // durable, so a failure before this line leaves the branch untouched.
        refs.updateBranch(ourBranch, commitId);

        return new MergeOutcome.Merged(commitId, mergedTree);
    }

    /**
     * The tree of the common ancestor the merge is measured against.
     *
     * <p>Unrelated histories have no common ancestor at all, in which case the
     * empty tree is the honest base: everything on both sides is an addition.
     *
     * <p>Where several lowest common ancestors exist — histories that merged from
     * each other in both directions — the first by object id is used. That is
     * deterministic and independent of argument order, and is the documented
     * simplification: no synthetic base is constructed by merging the bases
     * together.
     */
    private ObjectId baseTreeOf(ObjectId ours, ObjectId theirs) {
        List<ObjectId> bases = graph.mergeBases(ours, theirs);
        return bases.isEmpty() ? EMPTY_TREE_ID : treeOf(bases.getFirst());
    }

    private ObjectId treeOf(ObjectId commit) {
        return objects.readCommit(commit).tree();
    }

    private ObjectId requireBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            throw new RefException("A branch name is required");
        }
        return branches.getBranch(branch)
                .orElseThrow(() -> new RefException("Branch does not exist: " + branch));
    }

    private static String defaultMessage(String ourBranch, String theirBranch) {
        return "Merge branch '" + theirBranch + "' into " + ourBranch;
    }
}
