package com.gitforge.vcsapi.dto;

import java.time.Instant;

/**
 * The repository's releases as a set.
 *
 * <p>Counted from the releases the viewer may see. A draft never reaches this
 * calculation for someone who may not see one, so {@code drafts} is zero for
 * them because there were none to count rather than because a rule hid them.
 *
 * @param withMissingTag releases whose tag has since been deleted
 * @param medianIntervalSeconds the middle gap between publications, null with
 *     fewer than two; drafts have no publication date and are excluded
 */
public record ReleaseInsightsResponse(
        int total,
        int published,
        int drafts,
        int prereleases,
        int withExistingTag,
        int withMissingTag,
        Long medianIntervalSeconds,
        Instant firstPublished,
        Instant lastPublished) {
}
