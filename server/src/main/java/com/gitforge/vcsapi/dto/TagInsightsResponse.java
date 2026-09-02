package com.gitforge.vcsapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * The repository's tags as a set.
 *
 * @param medianIntervalSeconds the middle gap between annotated tags, null with
 *     fewer than two. Only annotated tags carry a time of their own, so a
 *     lightweight tag contributes to the counts and not to the cadence
 * @param withoutRelease tags that have no release against them
 */
public record TagInsightsResponse(
        int total,
        int annotated,
        int lightweight,
        Long medianIntervalSeconds,
        Instant firstTagged,
        Instant lastTagged,
        List<String> withoutRelease,
        List<Tag> tags) {

    /**
     * @param target what the ref holds; the tag object's id for an annotated tag
     * @param commit what it ultimately names, null when the chain cannot be followed
     */
    public record Tag(
            String name,
            boolean annotated,
            String target,
            String commit,
            Instant taggedAt) {
    }
}
