package com.gitforge.vcsapi.dto;

import java.util.List;

/**
 * What the object store holds, by type and by bytes.
 *
 * <p><strong>{@code storedObjects} and {@code scannedObjects} are different
 * numbers and must stay that way.</strong> The first is what the store reports
 * holding; the second is how much this scan actually read. When {@code truncated}
 * is true the per-type figures describe the scan and not the repository, and
 * presenting one as the other would be a fabricated statistic.
 *
 * @param unreadable objects enumerated but not readable; counted rather than
 *     dropped, so the arithmetic still closes
 */
public record StorageInsightsResponse(
        long storedObjects,
        int scannedObjects,
        long scannedBytes,
        boolean truncated,
        int unreadable,
        List<TypeUsage> byType) {

    public record TypeUsage(String type, int count, long bytes) {
    }
}
