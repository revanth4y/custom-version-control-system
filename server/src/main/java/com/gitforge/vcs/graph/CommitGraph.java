package com.gitforge.vcs.graph;

import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.storage.ObjectStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Traversal and reachability over the commit history.
 *
 * <p>The graph is not stored anywhere. It is implied entirely by the parent ids
 * inside commit objects, so this class walks the object store rather than any
 * index — there is no separate structure that could fall out of sync with the
 * commits themselves.
 *
 * <p>Nodes are identified by {@link ObjectId}, which is why that type has correct
 * value semantics: every visited set and every lookup here depends on it.
 *
 * <p><strong>Why every traversal keeps a visited set.</strong> Two reasons, and
 * only one of them is about cycles. The first is that merges make this a DAG
 * rather than a tree: after a merge, both parents usually lead back to shared
 * ancestry, so a naive walk revisits that shared history once per path into it —
 * exponentially, for a chain of merges. The visited set collapses that to linear
 * work. The second is defensive: a genuine cycle is impossible in well-formed
 * data, because a commit's id is a hash of bytes containing its parents' ids, so
 * a cycle would require a SHA-1 preimage. But a corrupted or deliberately forged
 * store could still present one, and traversal must terminate rather than spin.
 *
 * <p>Free of Spring, HTTP and persistence concerns; it needs only an
 * {@link ObjectStore}.
 */
public final class CommitGraph {

    private final ObjectStore store;

    public CommitGraph(ObjectStore store) {
        if (store == null) {
            throw new IllegalArgumentException("Object store must not be null");
        }
        this.store = store;
    }

    /**
     * Breadth-first traversal of a commit and its ancestors.
     *
     * <p>Order is by distance from {@code start}: the starting commit first, then
     * every parent in declared order, then their parents, and so on. A commit
     * reachable by several paths is emitted exactly once, at its shallowest
     * distance — so in a diamond history the shared ancestor appears after both
     * sides, not between them.
     *
     * @return the commits reachable from {@code start}, including {@code start}
     */
    public List<ObjectId> bfs(ObjectId start) {
        return walk(start).toList();
    }

    /**
     * The same traversal as {@link #bfs}, produced one commit at a time.
     *
     * <p>Identical order, and deliberately the only implementation of it —
     * {@link #bfs} is this method collected into a list. Two hand-written copies
     * of a traversal are two things that can disagree, and an ordering that
     * differed between the paged and unpaged views of the same history would be
     * a defect no test of either one alone would catch.
     *
     * <p><strong>Why laziness is the point.</strong> A caller that wants the
     * first thirty commits of a long history should read thirty commits, not all
     * of them. Collecting the whole reachable set first and discarding the tail
     * is work proportional to the repository rather than to the question, and it
     * is the reason history could not be paged before: every page would have
     * cost a full walk.
     *
     * <p>The stream is lazy but not parallel-safe, and it reads from the object
     * store as it is consumed. Consume it before the store changes underneath it,
     * and close it — or collect it — rather than abandoning it part-way.
     *
     * @return the commits reachable from {@code start}, including {@code start}
     */
    public Stream<ObjectId> walk(ObjectId start) {
        requireId(start);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(new BreadthFirstIterator(start), Spliterator.ORDERED | Spliterator.DISTINCT),
                false);
    }

    /**
     * Breadth-first order, advanced on demand.
     *
     * <p>Holds exactly the state the recursive-free loop held before: the queue
     * of commits still to visit, and the set of everything ever queued. Parents
     * are read when a commit is dequeued, so nothing beyond the frontier is
     * touched until the caller asks for it.
     */
    private final class BreadthFirstIterator implements Iterator<ObjectId> {

        private final Set<ObjectId> visited = new HashSet<>();
        private final Queue<ObjectId> queue = new ArrayDeque<>();

        private BreadthFirstIterator(ObjectId start) {
            visited.add(start);
            queue.add(start);
        }

        @Override
        public boolean hasNext() {
            return !queue.isEmpty();
        }

        @Override
        public ObjectId next() {
            if (queue.isEmpty()) {
                throw new NoSuchElementException("History is exhausted");
            }
            ObjectId current = queue.remove();

            for (ObjectId parent : parentsOf(current)) {
                // Marked on enqueue, not on dequeue: otherwise a commit reachable
                // by two paths at the same depth would be queued twice.
                if (visited.add(parent)) {
                    queue.add(parent);
                }
            }
            return current;
        }
    }

    /**
     * Depth-first traversal of a commit and its ancestors, in preorder.
     *
     * <p>Visits {@code start}, then follows its first parent as deep as that line
     * goes before considering the second parent. Written with an explicit stack
     * rather than recursion, because a long linear history is exactly the case
     * that would overflow the JVM stack.
     *
     * <p>Implemented independently of {@link #bfs}: the two differ in the order
     * history is explored, which is the entire point of having both.
     *
     * @return the commits reachable from {@code start}, including {@code start}
     */
    public List<ObjectId> dfs(ObjectId start) {
        requireId(start);

        List<ObjectId> order = new ArrayList<>();
        Set<ObjectId> visited = new HashSet<>();
        Deque<ObjectId> stack = new ArrayDeque<>();

        stack.push(start);

        while (!stack.isEmpty()) {
            ObjectId current = stack.pop();
            // Checked on pop rather than on push: a commit can be pushed by
            // several descendants before it is ever reached.
            if (!visited.add(current)) {
                continue;
            }
            order.add(current);

            // Pushed in reverse so the first parent is popped first, making the
            // first-parent line the one explored deepest.
            List<ObjectId> parents = parentsOf(current);
            for (int i = parents.size() - 1; i >= 0; i--) {
                if (!visited.contains(parents.get(i))) {
                    stack.push(parents.get(i));
                }
            }
        }
        return order;
    }

    /**
     * Whether {@code ancestor} can be reached from {@code descendant} by
     * following parent links.
     *
     * <p>Reflexive: a commit is considered an ancestor of itself, matching the
     * conventional definition used when asking whether one revision is already
     * contained in another.
     */
    public boolean isAncestor(ObjectId ancestor, ObjectId descendant) {
        requireId(ancestor);
        requireId(descendant);

        if (ancestor.equals(descendant)) {
            return true;
        }

        Set<ObjectId> visited = new HashSet<>();
        Queue<ObjectId> queue = new ArrayDeque<>();
        visited.add(descendant);
        queue.add(descendant);

        while (!queue.isEmpty()) {
            for (ObjectId parent : parentsOf(queue.remove())) {
                // Answered as soon as it is known, rather than walking the rest
                // of history first.
                if (parent.equals(ancestor)) {
                    return true;
                }
                if (visited.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return false;
    }

    /** Every commit reachable from {@code start}, including {@code start} itself. */
    public Set<ObjectId> ancestorsOf(ObjectId start) {
        return new LinkedHashSet<>(bfs(start));
    }

    /**
     * The lowest common ancestors of two commits.
     *
     * <p>A commit is a common ancestor when it is reachable from both. The
     * <em>lowest</em> ones are those that are not themselves ancestors of another
     * common ancestor — the frontier closest to the two tips, which is what a
     * three-way merge needs as its base.
     *
     * <p>Usually there is exactly one. Histories that cross over — two branches
     * that merged from each other in both directions — genuinely have several,
     * and none of them is more correct than the others, so all are returned.
     *
     * <p>Ordered by object id. That is arbitrary but stable, and deliberately
     * independent of traversal order, so swapping the arguments cannot change the
     * result.
     */
    public List<ObjectId> mergeBases(ObjectId a, ObjectId b) {
        requireId(a);
        requireId(b);

        Set<ObjectId> reachableFromA = ancestorsOf(a);
        List<ObjectId> common = bfs(b).stream()
                .filter(reachableFromA::contains)
                .toList();

        // Discard any candidate that another candidate can reach: if x is a
        // proper ancestor of y, then y is the lower of the two.
        List<ObjectId> lowest = new ArrayList<>();
        for (ObjectId candidate : common) {
            boolean supersededByAnother = common.stream()
                    .anyMatch(other -> !other.equals(candidate) && isAncestor(candidate, other));
            if (!supersededByAnother) {
                lowest.add(candidate);
            }
        }

        return lowest.stream().sorted(Comparator.naturalOrder()).toList();
    }

    /**
     * A single lowest common ancestor, or empty when the histories are unrelated.
     *
     * <p>When several exist, the first by object id is chosen so the answer is
     * deterministic and symmetric in its arguments.
     */
    public Optional<ObjectId> findCommonAncestor(ObjectId a, ObjectId b) {
        return mergeBases(a, b).stream().findFirst();
    }

    /**
     * Reads a commit, failing loudly if history references something that is not
     * present or is not a commit.
     */
    private List<ObjectId> parentsOf(ObjectId id) {
        Commit commit = store.readCommit(id);
        return commit.parents();
    }

    private static void requireId(ObjectId id) {
        if (id == null) {
            throw new IllegalArgumentException("Commit id must not be null");
        }
    }
}
