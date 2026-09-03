package com.gitforge.vcsapi.dto;

/**
 * What kinds of reference the repository holds.
 *
 * @param commitsOnlyTagsProtect commits reachable from a tag and from nothing
 *     else — no branch, no HEAD, no tracking ref. How much history would become
 *     collectible if the tags went away. Zero is a real answer, not a gap
 * @param headBranch the branch HEAD names, or null when HEAD is detached
 */
public record RefInsightsResponse(
        int branches,
        int tags,
        int remoteTrackingRefs,
        int remotes,
        int total,
        boolean headAttached,
        String headBranch,
        int commitsOnlyTagsProtect) {
}
