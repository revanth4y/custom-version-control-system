package com.gitforge.vcs.insights;

import com.gitforge.vcs.gc.GarbageCollector;
import com.gitforge.vcs.gc.GcReport;
import com.gitforge.vcs.ref.ReferenceRoots;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.worktree.WorkTreeState;

/**
 * How much of the store is spoken for, and how much is merely still there.
 *
 * <p>Split into two operations on purpose, because they cost wildly different
 * amounts and a caller must be able to choose.
 *
 * <p><strong>{@link #cheapCounts()} is a count and a directory listing.</strong>
 * It takes no lock and is safe on any page load.
 *
 * <p><strong>{@link #scan()} runs a real reachability sweep and holds the
 * repository's exclusive lock for its whole duration.</strong> Writers wait. It
 * must never be triggered as a side effect of opening a page — a statistics
 * screen that quietly blocks commits every time somebody looks at it is a worse
 * problem than the one it was showing. It is here as a deliberate, explicitly
 * requested operation, and callers are expected to present it as one.
 *
 * <p>Nothing is collected. {@link GarbageCollector#report()} computes what a
 * sweep would remove and removes none of it, so asking this question never
 * changes the repository.
 *
 * <p>The roots are {@link ReferenceRoots}' — the same ones collection and
 * statistics use — so the three cannot disagree about what is reachable.
 */
public final class ReachabilityHealth {

    private final ObjectStore objects;
    private final RefStore refs;
    private final WorkTreeState workTree;
    private final GarbageCollector collector;

    public ReachabilityHealth(
            ObjectStore objects, RefStore refs, WorkTreeState workTree, GarbageCollector collector) {

        if (objects == null || refs == null || collector == null) {
            throw new IllegalArgumentException("Reachability health needs a store, refs and a collector");
        }
        this.objects = objects;
        this.refs = refs;
        this.workTree = workTree;
        this.collector = collector;
    }

    /**
     * What can be known without a sweep.
     *
     * @param roots how many references speak for something, duplicates included,
     *     matching what a sweep would report
     */
    public record Counts(long storedObjects, int roots) {
    }

    /**
     * The result of an explicit sweep.
     *
     * @param durationMs how long the scan itself took, so the cost is visible
     *     rather than merely warned about
     */
    public record Scan(
            long storedObjects,
            long reachableObjects,
            int unreachableObjects,
            long unreachableBytes,
            int roots,
            int retained,
            boolean truncated,
            long durationMs) {

        /**
         * Nothing is unreachable: every object is spoken for.
         *
         * <p>False when the sweep was truncated, because a scan that stopped early
         * has not shown anything about the objects it never reached.
         */
        public boolean fullyReachable() {
            return !truncated && unreachableObjects == 0;
        }
    }

    /** Cheap: a count and the root list. No lock, no traversal. */
    public Counts cheapCounts() {
        return new Counts(objects.count(), ReferenceRoots.of(refs, workTree).size());
    }

    /**
     * A full reachability sweep, computing what could be collected and collecting
     * nothing.
     *
     * <p><strong>Takes the exclusive lock.</strong> Call only when a person has
     * asked for it.
     */
    public Scan scan() {
        GcReport report = collector.report();

        return new Scan(
                report.storedObjects(),
                report.reachableObjects(),
                report.unreachable().size(),
                report.unreachableBytes(),
                report.roots(),
                report.retained().size(),
                report.truncated(),
                report.duration().toMillis());
    }
}
