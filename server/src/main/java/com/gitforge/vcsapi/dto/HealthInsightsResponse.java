package com.gitforge.vcsapi.dto;

/**
 * Repository health: what is spoken for, and whether the bytes verify.
 *
 * <p><strong>The reachability scan is opt-in.</strong> It walks every object and
 * holds the repository's exclusive lock while it does, so writers wait. Without
 * {@code scan=true} only the cheap counts are returned and {@code scanned} is
 * false — a statistics request must never block commits as a side effect of
 * being made.
 *
 * <p>{@code integrity} is one of {@code HEALTHY}, {@code DAMAGED},
 * {@code TRUNCATED} or {@code NOT_VERIFIED}, and those four are kept apart on
 * purpose. A truncated scan has not shown a repository sound, and an empty one
 * has not been shown to be anything; collapsing either into healthy would be
 * claiming something no scan established.
 *
 * @param reachableObjects null unless a scan was run
 * @param unreachableObjects null unless a scan was run
 * @param scanDurationMs how long the sweep took, so its cost is visible
 */
public record HealthInsightsResponse(
        long storedObjects,
        int roots,
        boolean scanned,
        Long reachableObjects,
        Integer unreachableObjects,
        Long unreachableBytes,
        Integer retained,
        Boolean scanTruncated,
        Boolean fullyReachable,
        Long scanDurationMs,
        String integrity,
        int verifiedObjects,
        int damagedObjects,
        boolean integrityTruncated) {
}
