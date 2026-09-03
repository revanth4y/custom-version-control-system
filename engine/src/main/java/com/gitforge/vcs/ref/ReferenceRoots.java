package com.gitforge.vcs.ref;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.worktree.WorkTreeState;

import java.util.ArrayList;
import java.util.List;

/**
 * Every object this repository speaks for, in one place.
 *
 * <p><strong>Why this class exists.</strong> The root set is the correctness
 * argument behind two entirely different questions — what garbage collection may
 * delete, and what the repository's statistics describe — and for a while they
 * answered it differently. Collection read branches, HEAD, remote-tracking refs
 * and tags; statistics read branches alone. A commit reachable only through a tag
 * was therefore protected from deletion and absent from every figure, which is
 * not two opinions about one repository but one of them being wrong.
 *
 * <p>Having a second implementation was what allowed them to drift, so there is
 * now one. A root added here is a root everywhere, and the two cannot disagree
 * again without someone deliberately bypassing this.
 *
 * <p><strong>What counts as a root:</strong>
 *
 * <ul>
 *   <li>every branch tip;
 *   <li>whatever HEAD resolves to — which for a detached HEAD is a commit no
 *       branch names;
 *   <li>every remote-tracking ref, because a fetched tip is spoken for even
 *       though no local branch reaches it;
 *   <li>every tag, which is the whole point of a tag — and the only root that may
 *       name something other than a commit, since an annotated tag names a tag
 *       object;
 *   <li>the tree a working tree has materialized, which is a tree rather than a
 *       commit and is deliberately not derived from HEAD.
 * </ul>
 *
 * <p><strong>Duplicates are kept, deliberately.</strong> An attached HEAD names a
 * commit a branch also names. Filtering here would make a reported root count
 * depend on how the roots happened to overlap, so callers that need distinct ids
 * deduplicate for themselves — the traversal does anyway.
 */
public final class ReferenceRoots {

    private ReferenceRoots() {
    }

    /**
     * Every root, in a stable order, duplicates included.
     *
     * @param workTree may be null, for a repository with no working tree; a null
     *     working tree contributes no root, which is not the same as contributing
     *     an empty one and is why it is not silently defaulted
     */
    public static List<ObjectId> of(RefStore refs, WorkTreeState workTree) {
        if (refs == null) {
            throw new IllegalArgumentException("A root set needs a reference store");
        }

        List<ObjectId> roots = new ArrayList<>();

        for (String branch : refs.listBranches()) {
            refs.getBranch(branch).ifPresent(roots::add);
        }
        refs.resolveHead().ifPresent(roots::add);

        // Fetched tips. An object reachable only through a remote-tracking ref is
        // still an object this repository asked for and can still show, so it is
        // spoken for exactly as a branch tip is.
        refs.listRemoteRefs().forEach(ref -> roots.add(ref.commit()));

        // Tags. A tag exists precisely so that a point in history stays reachable
        // after the branch that produced it has moved on or been deleted. Unlike
        // every other root this may name a tag object rather than a commit;
        // following it on to its target is the caller's business.
        for (String tag : refs.listTags()) {
            refs.getTag(tag).ifPresent(roots::add);
        }

        if (workTree != null) {
            workTree.materializedTree().ifPresent(roots::add);
        }
        return roots;
    }
}
