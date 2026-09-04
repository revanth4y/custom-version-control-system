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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Parent edges already read, for the life of this graph.
     *
     * <p>Concurrent because a graph may be shared by threads reading at the
     * same time, and because putting the same immutable answer twice is
     * harmless either way.
     */
    private final Map<ObjectId, List<ObjectId>> parents = new ConcurrentHashMap<>();

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
     * Ancestors of {@code start} that lie outside {@code boundary}, plus the
     * boundary commits the walk arrived at.
     *
     * <p>The walk stops descending the moment it reaches a commit inside the
     * boundary. That is not an approximation: {@code boundary} is always an
     * ancestor-closed set here, so everything below a boundary commit is inside
     * the boundary too and cannot belong to the answer. Stopping is what turns
     * "walk the whole history once per reference" into "walk only the part that
     * is actually different".
     *
     * @param boundary an ancestor-closed set to stop at; may be empty, in which
     *     case this is an ordinary full ancestry walk
     * @param frontier collects the boundary commits reached, in encounter order;
     *     these are the points where the two histories join
     * @return the ancestors of {@code start} not in {@code boundary}, in
     *     breadth-first order
     */
    public Set<ObjectId> ancestorsOutside(
            ObjectId start, Set<ObjectId> boundary, Set<ObjectId> frontier) {
        requireId(start);

        Set<ObjectId> outside = new LinkedHashSet<>();
        Set<ObjectId> seen = new HashSet<>();
        Queue<ObjectId> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);

        while (!queue.isEmpty()) {
            ObjectId id = queue.remove();
            if (boundary.contains(id)) {
                if (frontier != null) {
                    frontier.add(id);
                }
                continue;
            }
            outside.add(id);
            for (ObjectId parent : parentsOf(id)) {
                if (seen.add(parent)) {
                    queue.add(parent);
                }
            }
        }
        return outside;
    }

    /**
     * Adds every ancestor of {@code start} to {@code into}, skipping anything
     * already there or inside {@code boundary}.
     *
     * <p>For building the union of many references' histories without replaying
     * the shared part once per reference. Both sets are ancestor-closed while
     * this runs, so declining to descend past a commit in either of them cannot
     * miss anything, and the result is the same set the naive union produces —
     * in the same order, because sources are still visited one after another
     * rather than interleaved.
     */
    public void collectAncestors(ObjectId start, Set<ObjectId> boundary, Set<ObjectId> into) {
        requireId(start);

        Set<ObjectId> seen = new HashSet<>();
        Queue<ObjectId> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);

        while (!queue.isEmpty()) {
            ObjectId id = queue.remove();
            if (into.contains(id) || boundary.contains(id)) {
                continue;
            }
            into.add(id);
            for (ObjectId parent : parentsOf(id)) {
                if (seen.add(parent)) {
                    queue.add(parent);
                }
            }
        }
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
    /**
     * The parents of one commit, remembered after the first read.
     *
     * <p>Safe to remember for as long as this graph lives, and safe for a
     * reason stronger than careful invalidation: an object id is the hash of
     * the object, so the parents of a given id cannot change. A mutation adds
     * commits with new ids; it never gives an existing id different parents.
     * There is therefore no stale entry to invalidate, and no mutation this
     * cache can survive incorrectly.
     *
     * <p>What is remembered is the edge, not the object. Every commit still
     * arrives through {@link ObjectStore#readCommit}, which verifies the bytes
     * against the id before returning them; the memo only stops the same
     * verified answer being recomputed on the walk after this one.
     */
    private List<ObjectId> parentsOf(ObjectId id) {
        List<ObjectId> known = parents.get(id);
        if (known != null) {
            return known;
        }
        Commit commit = store.readCommit(id);
        List<ObjectId> resolved = commit.parents();
        parents.put(id, resolved);
        return resolved;
    }

    private static void requireId(ObjectId id) {
        if (id == null) {
            throw new IllegalArgumentException("Commit id must not be null");
        }
    }
}
