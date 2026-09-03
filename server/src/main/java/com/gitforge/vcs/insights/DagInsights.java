package com.gitforge.vcs.insights;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The shape of the commit graph, as opposed to its contents.
 *
 * <p>These are the figures GitHub cannot show, because they describe the DAG
 * itself rather than the work in it: how much of the history is merges, how deep
 * the ancestry runs, how many independent roots exist.
 *
 * <p><strong>Given commits rather than a way to find them.</strong> Which commits
 * count is decided once, by {@code ReferenceRoots}, and passing that decision in
 * is what stops this from becoming a fourth opinion about reachability.
 *
 * <p>Depth is computed iteratively rather than by recursion. A deep history would
 * overflow the stack, and a statistic that crashes on a long-lived repository is
 * worse than no statistic.
 */
public final class DagInsights {

    private final ObjectStore objects;
    private final CommitGraph graph;

    public DagInsights(ObjectStore objects, CommitGraph graph) {
        if (objects == null || graph == null) {
            throw new IllegalArgumentException("DAG insights need a store and a graph");
        }
        this.objects = objects;
        this.graph = graph;
    }

    /**
     * @param commits distinct commits in the counted set
     * @param merges commits with more than one parent
     * @param nonMerges the rest
     * @param mergeRatio merges as a fraction of all commits; zero for no commits,
     *     which is more honest than a division nobody can perform
     * @param roots commits with no parent — normally one, more when unrelated
     *     histories have been brought together
     * @param maxDepth the longest chain of parents from any counted commit,
     *     counting the commit itself, so a single commit has depth 1
     * @param maxParents the most parents any one commit has, which is 2 for an
     *     ordinary merge and more for an octopus
     */
    public record Shape(
            int commits,
            int merges,
            int nonMerges,
            double mergeRatio,
            int roots,
            List<ObjectId> rootCommits,
            int maxDepth,
            int maxParents) {
    }

    /** The shape of the graph spanned by {@code commits}. */
    public Shape shapeOf(Collection<ObjectId> commits) {
        Set<ObjectId> counted = new LinkedHashSet<>(commits);

        int merges = 0;
        int maxParents = 0;
        List<ObjectId> rootCommits = new java.util.ArrayList<>();
        Map<ObjectId, List<ObjectId>> parents = new HashMap<>();

        for (ObjectId id : counted) {
            Optional<Commit> commit = readQuietly(id);
            if (commit.isEmpty()) {
                continue;
            }
            List<ObjectId> theirParents = commit.get().parents();
            parents.put(id, theirParents);

            if (theirParents.size() > 1) {
                merges++;
            }
            if (theirParents.isEmpty()) {
                rootCommits.add(id);
            }
            maxParents = Math.max(maxParents, theirParents.size());
        }

        int total = parents.size();
        int nonMerges = total - merges;
        double ratio = total == 0 ? 0.0 : (double) merges / total;

        return new Shape(
                total,
                merges,
                nonMerges,
                ratio,
                rootCommits.size(),
                List.copyOf(rootCommits),
                deepestChain(parents),
                maxParents);
    }

    /**
     * The longest chain of parents within the counted set.
     *
     * <p>Bounded to the set on purpose: a commit whose parent was not counted —
     * because nothing reachable speaks for it — ends the chain there rather than
     * wandering into history the caller decided was out of scope.
     *
     * <p>Iterative, with memoised depths, so the cost is one visit per commit
     * rather than one per path. A branching history has exponentially many paths
     * and walking them all is how a statistic becomes a hang.
     */
    private int deepestChain(Map<ObjectId, List<ObjectId>> parents) {
        Map<ObjectId, Integer> depth = new HashMap<>();
        int deepest = 0;

        for (ObjectId start : parents.keySet()) {
            if (depth.containsKey(start)) {
                continue;
            }
            Deque<ObjectId> stack = new ArrayDeque<>();
            stack.push(start);

            while (!stack.isEmpty()) {
                ObjectId current = stack.peek();
                if (depth.containsKey(current)) {
                    stack.pop();
                    continue;
                }

                boolean waiting = false;
                int best = 0;
                for (ObjectId parent : parents.getOrDefault(current, List.of())) {
                    if (!parents.containsKey(parent)) {
                        // Outside the counted set: the chain stops here.
                        continue;
                    }
                    Integer known = depth.get(parent);
                    if (known == null) {
                        stack.push(parent);
                        waiting = true;
                    } else {
                        best = Math.max(best, known);
                    }
                }
                if (waiting) {
                    continue;
                }
                stack.pop();
                depth.put(current, best + 1);
                deepest = Math.max(deepest, best + 1);
            }
        }
        return deepest;
    }

    /**
     * Whether one commit can reach another, deferring to the existing graph.
     *
     * <p>Here so callers describing the DAG do not each reach for their own
     * traversal.
     */
    public boolean reaches(ObjectId ancestor, ObjectId descendant) {
        return graph.isAncestor(ancestor, descendant);
    }

    private Optional<Commit> readQuietly(ObjectId id) {
        try {
            return Optional.of(objects.readCommit(id));
        } catch (CorruptObjectException ex) {
            return Optional.empty();
        }
    }
}
