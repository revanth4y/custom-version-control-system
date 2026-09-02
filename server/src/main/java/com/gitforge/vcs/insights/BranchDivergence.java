package com.gitforge.vcs.insights;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.ref.RefStore;

import java.util.ArrayList;
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

    /** Every branch measured against {@code base}, which may be null for nothing. */
    public List<Branch> against(ObjectId base) {
        Optional<String> current = branches.currentBranch();
        Set<ObjectId> baseAncestry = base == null ? Set.of() : graph.ancestorsOf(base);

        List<Branch> result = new ArrayList<>();
        for (String name : branches.listBranches()) {
            Optional<ObjectId> tip = branches.getBranch(name);
            if (tip.isEmpty()) {
                // A branch whose tip cannot be read is not described rather than
                // described wrongly.
                continue;
            }

            Set<ObjectId> branchAncestry = graph.ancestorsOf(tip.get());

            int ahead = countMissing(branchAncestry, baseAncestry);
            int behind = countMissing(baseAncestry, branchAncestry);
            boolean related = shareAnything(branchAncestry, baseAncestry);

            result.add(new Branch(
                    name,
                    tip.get(),
                    ahead,
                    behind,
                    current.map(name::equals).orElse(false),
                    related));
        }
        return List.copyOf(result);
    }

    private static int countMissing(Set<ObjectId> from, Set<ObjectId> other) {
        int missing = 0;
        for (ObjectId id : from) {
            if (!other.contains(id)) {
                missing++;
            }
        }
        return missing;
    }

    private static boolean shareAnything(Set<ObjectId> a, Set<ObjectId> b) {
        Set<ObjectId> smaller = a.size() <= b.size() ? a : b;
        Set<ObjectId> larger = smaller == a ? b : a;
        for (ObjectId id : new LinkedHashSet<>(smaller)) {
            if (larger.contains(id)) {
                return true;
            }
        }
        return false;
    }
}
