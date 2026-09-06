package com.gitforge.vcs.insights;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.ref.RefStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * How far each branch has drifted from a comparison point.
 *
 * <p><strong>The definitions, stated before the code so they can be argued with:</strong>
 *
 * <ul>
 *   <li><em>ahead</em> — commits reachable from the branch tip and not from the
 *       base. Work this branch has that the base does not.
 *   <li><em>behind</em> — commits reachable from the base and not from the branch
 *       tip. Work the base has that this branch does not.
 * </ul>
 *
 * <p>Both are counted from the symmetric difference of the two ancestries, which
 * is the same quantity {@code git rev-list --count base..branch} reports. A merge
 * base is not needed to compute it and is deliberately not used: the difference
 * is what it is regardless of where the histories last agreed.
 *
 * <p><strong>Unrelated histories are described, not refused.</strong> Two roots
 * that never met have no common ancestor, and the honest answer is that each side
 * holds everything the other lacks — so ahead and behind are simply both totals.
 * {@code related} says which case it is, so a caller can tell "diverged by
 * three" from "shares nothing at all" rather than seeing two numbers and
 * guessing.
 */
public final class BranchDivergence {

    private final RefStore refs;
    private final BranchService branches;
    private final CommitGraph graph;

    public BranchDivergence(RefStore refs, BranchService branches, CommitGraph graph) {
        if (refs == null || branches == null || graph == null) {
            throw new IllegalArgumentException("Divergence needs references, branches and a graph");
        }
        this.refs = refs;
        this.branches = branches;
        this.graph = graph;
    }

    /**
     * One branch measured against the base.
     *
     * @param current whether HEAD names this branch
     * @param related whether the two histories share any commit at all
     */
    public record Branch(
            String name,
            ObjectId tip,
            int ahead,
            int behind,
            boolean current,
            boolean related) {

        /** Neither ahead nor behind: the same history. */
        public boolean identical() {
            return ahead == 0 && behind == 0;
        }

        /** Strictly behind: an ancestor of the base. */
        public boolean ancestor() {
            return ahead == 0 && behind > 0;
        }

        /** Strictly ahead: a descendant of the base. */
        public boolean descendant() {
            return ahead > 0 && behind == 0;
        }

        /** Both sides hold something the other lacks. */
        public boolean diverged() {
            return ahead > 0 && behind > 0;
        }
    }

    /**
     * Every branch measured against whatever HEAD resolves to.
     *
     * <p>An empty list for a repository with no branches, and — when HEAD resolves
     * to nothing, as on a repository with no commits — every branch reports its
     * whole history as ahead of nothing, which is exactly what it is.
     */
    public List<Branch> againstHead() {
        return against(refs.resolveHead().orElse(null));
    }

    /**
     * Every branch measured against {@code base}, which may be null for nothing.
     *
     * <p>The arithmetic is unchanged - ahead is what the branch reaches and the
     * base does not, behind is the reverse, related is whether they share
     * anything at all - but it is no longer arrived at by walking the whole
     * history once per branch.
     *
     * <p>The base ancestry is walked once. Each branch is then walked only
     * outwards from its tip, stopping wherever it meets that ancestry, which
     * gives its ahead set directly and records the commits where the two
     * histories join. Everything the branch and the base share lies below those
     * join points, so the size of the shared part depends on the join points
     * alone - and branches sharing join points share the answer, which is why it
     * is computed once per distinct set of them rather than once per branch. In
     * the ordinary case, where branches sit on the mainline, that is one
     * computation for all of them instead of one each.
     */
    public List<Branch> against(ObjectId base) {
        Optional<String> current = branches.currentBranch();
        Set<ObjectId> baseAncestry = base == null ? Set.of() : graph.ancestorsOf(base);

        // Keyed by the join points, because two branches meeting the base at the
        // same commits share every commit below them and so share this count.
        Map<Set<ObjectId>, Integer> sharedByFrontier = new HashMap<>();

        List<Branch> result = new ArrayList<>();
        for (String name : branches.listBranches()) {
            Optional<ObjectId> tip = branches.getBranch(name);
            if (tip.isEmpty()) {
                // A branch whose tip cannot be read is not described rather than
                // described wrongly.
                continue;
            }

            Set<ObjectId> frontier = new LinkedHashSet<>();
            Set<ObjectId> onlyOnBranch = graph.ancestorsOutside(tip.get(), baseAncestry, frontier);

            int shared = sharedByFrontier.computeIfAbsent(
                    frontier, joins -> countReachable(joins, baseAncestry));

            result.add(new Branch(
                    name,
                    tip.get(),
                    onlyOnBranch.size(),
                    baseAncestry.size() - shared,
                    current.map(name::equals).orElse(false),
                    shared > 0));
        }
        return List.copyOf(result);
    }

    /**
     * How much of the base ancestry the branch also reaches.
     *
     * <p>Everything reachable from the join points, all of which lie inside the
     * base ancestry, so the walk never leaves it. An empty set of join points
     * means the histories never meet: nothing is shared, the branch is
     * unrelated, and it is behind by the whole base ancestry - which is exactly
     * what the set arithmetic said before.
     */
    private int countReachable(Set<ObjectId> joins, Set<ObjectId> withinBase) {
        if (joins.isEmpty()) {
            return 0;
        }
        Set<ObjectId> reached = new LinkedHashSet<>();
        for (ObjectId join : joins) {
            graph.collectAncestors(join, Set.of(), reached);
        }
        // Defensive rather than expected: the join points are inside the base
        // ancestry and it is ancestor-closed, so the walk cannot leave it.
        reached.retainAll(withinBase);
        return reached.size();
    }
}
