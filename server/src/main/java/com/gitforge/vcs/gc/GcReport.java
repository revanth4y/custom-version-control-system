package com.gitforge.vcs.gc;

import com.gitforge.vcs.object.ObjectId;

import java.time.Duration;
import java.util.List;

/**
 * What a sweep found, and what it did about it.
 *
 * <p>Reporting and collecting return the same shape deliberately. A caller
 * deciding whether to collect is looking at exactly the figures collecting would
 * have acted on, and a caller reading the result of a collection can see what was
 * left behind as well as what went.
 *
 * @param storedObjects objects the store held when the sweep began
 * @param reachableObjects objects reached from the complete root set
 * @param roots how many roots the traversal started from
 * @param unreachable every object no root reaches, whether or not it was removed
 * @param collected the objects actually removed; empty for a report
 * @param reclaimedBytes bytes freed by {@link #collected}; zero for a report
 * @param retained unreachable objects deliberately kept, with a reason each
 * @param temporaryFiles staging files found in the store, never removed
 * @param truncated true if the store was larger than the sweep's ceiling, in
 *     which case nothing was collected and the figures describe nothing
 * @param collectionPerformed whether this was a collection or only a report
 * @param duration how long the sweep took
 */
public record GcReport(
        long storedObjects,
        long reachableObjects,
        int roots,
        List<UnreachableObject> unreachable,
        List<ObjectId> collected,
        long reclaimedBytes,
        List<RetainedObject> retained,
        List<String> temporaryFiles,
        boolean truncated,
        boolean collectionPerformed,
        Duration duration) {

    public GcReport {
        unreachable = List.copyOf(unreachable);
        collected = List.copyOf(collected);
        retained = List.copyOf(retained);
        temporaryFiles = List.copyOf(temporaryFiles);
    }

    /** Bytes the unreachable objects occupy, whether or not they were collected. */
    public long unreachableBytes() {
        return unreachable.stream().mapToLong(UnreachableObject::bytes).sum();
    }

    /**
     * An object that was unreachable and was kept anyway.
     *
     * <p>A sweep that silently declined to delete something would be impossible to
     * tell from one that found nothing, so every retention is named along with why
     * it happened.
     */
    public record RetainedObject(ObjectId id, Reason reason) {

        public RetainedObject {
            if (id == null) {
                throw new IllegalArgumentException("A retained object must have an id");
            }
            if (reason == null) {
                throw new IllegalArgumentException("A retained object must have a reason");
            }
        }
    }

    /** Why an unreachable object was kept. */
    public enum Reason {

        /**
         * The object could not be read back, so what it is and what it references
         * are both unknown. Deleting bytes that cannot be identified is how a
         * repair turns into a loss.
         */
        DAMAGED
    }
}
