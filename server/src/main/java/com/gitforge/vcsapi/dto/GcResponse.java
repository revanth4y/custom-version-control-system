package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.gc.GcReport;
import com.gitforge.vcs.object.ObjectId;

import java.time.Instant;
import java.util.List;

/**
 * What a sweep found, over the wire.
 *
 * <p>Reporting and collecting answer with the same shape, because they are the
 * same measurement — one of them acts on it. A caller can therefore show the
 * outcome of a collection with the code that showed the preview.
 *
 * @param storedObjects objects held when the sweep began
 * @param reachableObjects objects reached from every root
 * @param roots how many references the traversal started from
 * @param unreachableObjects how many objects nothing reaches
 * @param unreachableBytes what those objects occupy on disk, compressed
 * @param unreachable each of them, newest information first read from the object
 * @param collectedObjects how many were removed; zero for a report
 * @param collected the ids removed, so a caller can say which
 * @param reclaimedBytes what removing them freed; zero for a report
 * @param retained unreachable objects deliberately kept, with a reason
 * @param temporaryFiles staging files found in the store, which are never removed
 * @param truncated true if the store exceeded the sweep ceiling, in which case
 *     nothing was examined and nothing was collected
 * @param collectionPerformed false for a report, true for a collection
 * @param checkedAt when the sweep ran
 * @param durationMs how long it took
 */
public record GcResponse(
        long storedObjects,
        long reachableObjects,
        int roots,
        int unreachableObjects,
        long unreachableBytes,
        List<UnreachableObjectResponse> unreachable,
        int collectedObjects,
        List<String> collected,
        long reclaimedBytes,
        List<RetainedObjectResponse> retained,
        List<String> temporaryFiles,
        boolean truncated,
        boolean collectionPerformed,
        Instant checkedAt,
        long durationMs) {

    /** One object nothing reaches. */
    public record UnreachableObjectResponse(String id, String type, long bytes) {
    }

    /** One unreachable object that was kept anyway, and why. */
    public record RetainedObjectResponse(String id, String reason) {
    }

    public static GcResponse from(GcReport report) {
        return new GcResponse(
                report.storedObjects(),
                report.reachableObjects(),
                report.roots(),
                report.unreachable().size(),
                report.unreachableBytes(),
                report.unreachable().stream()
                        .map(object -> new UnreachableObjectResponse(
                                object.id().toHex(),
                                object.type().name().toLowerCase(java.util.Locale.ROOT),
                                object.bytes()))
                        .toList(),
                report.collected().size(),
                report.collected().stream().map(ObjectId::toHex).toList(),
                report.reclaimedBytes(),
                report.retained().stream()
                        .map(retained -> new RetainedObjectResponse(
                                retained.id().toHex(),
                                retained.reason().name().toLowerCase(java.util.Locale.ROOT)))
                        .toList(),
                report.temporaryFiles(),
                report.truncated(),
                report.collectionPerformed(),
                Instant.now(),
                report.duration().toMillis());
    }
}
