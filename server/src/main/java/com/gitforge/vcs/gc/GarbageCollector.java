package com.gitforge.vcs.gc;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.repository.RepositoryLock;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.worktree.WorkTreeState;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reclaiming objects nothing can reach.
 *
 * <p>The engine has always been able to strand objects. Deleting a branch leaves
 * its commits behind, a merge that is computed and then refused leaves the trees
 * it built, and until now nothing could tell that those objects were unwanted,
 * let alone remove them. This does, in the only order that is safe:
 *
 * <pre>
 *   roots → reachable closure → difference → verify → delete
 * </pre>
 *
 * <p><strong>The root set is the whole correctness argument.</strong> Getting it
 * wrong does not produce a worse sweep, it produces a destroyed repository, so it
 * is enumerated from the reference store rather than assumed:
 *
 * <ul>
 *   <li>every branch tip;
 *   <li>whatever HEAD resolves to — which for a detached HEAD is a commit no
 *       branch names, and is exactly the case a branches-only traversal loses;
 *   <li>every remote-tracking ref, because a fetched tip is spoken for even
 *       though no local branch reaches it;
 *   <li>the tree recorded in {@link WorkTreeState}, when a working tree has been
 *       materialized. That is a tree rather than a commit, and it is deliberately
 *       not derived from HEAD, so nothing else in the root set implies it.
 * </ul>
 *
 * <p>There are no other persistent references. The engine has no tags — its
 * {@link com.gitforge.vcs.object.ObjectType} admits only blobs, trees and commits
 * — and no reflog, and the database stores no object ids at all, so
 * {@code refs/heads}, {@code refs/remotes}, {@code HEAD} and {@code WORKTREE} are
 * the complete set of places an object can be spoken for.
 *
 * <p><strong>Nothing is deleted unless the closure is complete.</strong> If any
 * object in it cannot be read — missing, or damaged past parsing — then what the
 * rest of the repository references is unknown, and an unknown reference is not
 * evidence of garbage. The sweep abandons the whole operation rather than delete
 * on a partial picture. The same applies to an unreachable object that will not
 * read back: it is reported and kept, because bytes nobody can identify are not
 * bytes anybody should destroy.
 *
 * <p>Temporary files are found and reported but never removed. See
 * {@link #temporaryFilesAreReportedNotCollected()}.
 */
public final class GarbageCollector {

    /**
     * The most objects one sweep will consider.
     *
     * <p>Mirrors the ceiling {@code IntegrityApiService} puts on verification, and
     * for the same reason: the cost scales with what is stored rather than with
     * the size of the answer. The difference is what happens at the limit.
     * Verification truncates and reports what it managed; a sweep cannot, because
     * a reachability calculation that stopped early would call reachable objects
     * garbage. Over the ceiling it collects nothing at all.
     */
    public static final int DEFAULT_MAX_SWEPT_OBJECTS = 10_000;

    private final ObjectStore objects;
    private final RefStore refs;
    private final WorkTreeState workTree;
    private final RepositoryLock lock;
    private final int maxSweptObjects;

    /**
     * @param workTree may be null, for a repository with no working tree; a null
     *     working tree contributes no root, which is not the same as contributing
     *     an empty one and is why it is not silently defaulted
     */
    public GarbageCollector(
            ObjectStore objects, RefStore refs, WorkTreeState workTree, RepositoryLock lock) {
        this(objects, refs, workTree, lock, DEFAULT_MAX_SWEPT_OBJECTS);
    }

    /** Visible for tests, so the ceiling can be exercised without ten thousand objects. */
    GarbageCollector(
            ObjectStore objects,
            RefStore refs,
            WorkTreeState workTree,
            RepositoryLock lock,
            int maxSweptObjects) {

        if (objects == null || refs == null || lock == null) {
            throw new IllegalArgumentException("A collector needs a store, references and a lock");
        }
        if (maxSweptObjects < 1) {
            throw new IllegalArgumentException("The sweep ceiling must be at least one object");
        }
        this.objects = objects;
        this.refs = refs;
        this.workTree = workTree;
        this.lock = lock;
        this.maxSweptObjects = maxSweptObjects;
    }

    /**
     * What a collection would remove, removing nothing.
     *
     * <p>Runs under the same exclusion as a collection so the answer is a
     * consistent snapshot rather than a torn view of a repository being written
     * to. That does hold writers up for the length of the scan, which at the scale
     * this engine is built for is the cheaper of the two mistakes.
     */
    public GcReport report() {
        return lock.exclusive(() -> sweep(false));
    }

    /**
     * Removes every object no root reaches.
     *
     * <p>Idempotent: a second call finds nothing, because the first left nothing.
     * A sweep of a repository with no garbage is a no-op that reports as much.
     */
    public GcReport collect() {
        return lock.exclusive(() -> sweep(true));
    }

    /**
     * Why a stray temporary file is reported rather than deleted.
     *
     * <p>A temporary file is created by {@code writeAtomically} immediately before
     * an object is moved into place, and removed in a {@code finally}. One that
     * still exists is therefore either a write happening right now, or the residue
     * of a process that was killed between the two. <em>Nothing on disk tells the
     * two apart.</em> They have the same prefix, the same shape, and the same
     * absence of an id.
     *
     * <p>Age would separate them, but only by choosing a duration that says how
     * long a write is allowed to take, and no such figure exists anywhere in this
     * repository. Inventing one would mean deleting a slow write's staging file
     * and failing a commit that was going to succeed.
     *
     * <p>So they are counted and named, which is more than anything could do
     * before, and left alone. The cost of keeping them is a few bytes; the cost of
     * removing the wrong one is a lost commit.
     *
     * <p>This method exists to hold the explanation. It has no behaviour.
     */
    static void temporaryFilesAreReportedNotCollected() {
        // Documentation only.
    }

    private GcReport sweep(boolean collecting) {
        Instant started = Instant.now();

        List<ObjectId> stored = objects.listIds();
        List<String> temporaryFiles = objects.temporaryFiles();

        if (stored.size() > maxSweptObjects) {
            return new GcReport(
                    stored.size(), 0, 0,
                    List.of(), List.of(), 0, List.of(), temporaryFiles,
                    true, false, Duration.between(started, Instant.now()));
        }

        List<ObjectId> roots = roots();
        Set<ObjectId> reachable = closure(roots);

        Set<ObjectId> unreachableIds = new LinkedHashSet<>(stored);
        unreachableIds.removeAll(reachable);

        List<UnreachableObject> unreachable = new ArrayList<>();
        List<GcReport.RetainedObject> retained = new ArrayList<>();
        List<ObjectId> collected = new ArrayList<>();
        long reclaimed = 0;

        for (ObjectId id : unreachableIds) {
            long bytes = objects.sizeOf(id);

            Optional<VcsObject> object;
            try {
                object = objects.read(id);
            } catch (CorruptObjectException ex) {
                // Damaged, so what it is and what it points at are both unknown.
                retained.add(new GcReport.RetainedObject(id, GcReport.Reason.DAMAGED));
                continue;
            }
            if (object.isEmpty()) {
                // Listed a moment ago and gone now. Nothing to remove, and nothing
                // to report as removed.
                continue;
            }

            unreachable.add(new UnreachableObject(id, object.get().type(), bytes));

            if (collecting && objects.delete(id)) {
                collected.add(id);
                reclaimed += bytes;
            }
        }

        return new GcReport(
                stored.size(),
                reachable.size(),
                roots.size(),
                unreachable,
                collected,
                reclaimed,
                retained,
                temporaryFiles,
                false,
                collecting,
                Duration.between(started, Instant.now()));
    }

    /**
     * Every object spoken for by a reference, before any traversal.
     *
     * <p>Duplicates are kept rather than filtered: an attached HEAD names a commit
     * a branch also names, and the traversal deduplicates anyway. Removing them
     * here would make the reported root count depend on how the roots happened to
     * overlap.
     */
    private List<ObjectId> roots() {
        List<ObjectId> roots = new ArrayList<>();

        for (String branch : refs.listBranches()) {
            refs.getBranch(branch).ifPresent(roots::add);
        }
        refs.resolveHead().ifPresent(roots::add);

        // Fetched tips. An object reachable only through a remote-tracking ref is
        // still an object this repository asked for and can still show, so it is
        // spoken for exactly as a branch tip is. Leaving these out would make an
        // ordinary sweep delete everything a fetch had just brought in.
        refs.listRemoteRefs().forEach(ref -> roots.add(ref.commit()));

        if (workTree != null) {
            workTree.materializedTree().ifPresent(roots::add);
        }
        return roots;
    }

    /**
     * Everything reachable from the roots, or a failure.
     *
     * <p>Roots may be commits or trees — the working tree records a tree — so each
     * object is dispatched on what it turns out to be rather than on where it came
     * from.
     */
    private Set<ObjectId> closure(List<ObjectId> roots) {
        Set<ObjectId> reachable = new LinkedHashSet<>();
        Deque<ObjectId> pending = new ArrayDeque<>(roots);

        while (!pending.isEmpty()) {
            ObjectId id = pending.pop();
            if (!reachable.add(id)) {
                continue;
            }

            VcsObject object;
            try {
                object = objects.read(id).orElseThrow(() -> new IncompleteReachabilityException(
                        "Object " + id + " is referenced but missing, so what is still needed "
                                + "cannot be established"));
            } catch (CorruptObjectException ex) {
                throw new IncompleteReachabilityException(
                        "Object " + id + " is damaged, so what it references cannot be read", ex);
            }

            switch (object) {
                case Commit commit -> {
                    pending.push(commit.tree());
                    commit.parents().forEach(pending::push);
                }
                case Tree tree -> tree.entries().stream().map(TreeEntry::id).forEach(pending::push);

                // Exhaustive over the sealed VcsObject rather than defaulted: a
                // fourth object type must not be able to appear here and be
                // traversed as if it referenced nothing.
                case Blob ignored -> {
                }
            }
        }
        return reachable;
    }
}
