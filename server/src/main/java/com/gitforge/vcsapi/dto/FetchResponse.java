package com.gitforge.vcsapi.dto;

import java.util.List;

/**
 * What a fetch brought back.
 *
 * @param updatedRefs the tracking refs written, as {@code origin/main}
 * @param receivedObjects objects newly stored; zero when nothing had changed
 * @param skippedBranches names the remote offered that this repository will not
 *     track, reported rather than swallowed so it is clear why one never appeared
 */
public record FetchResponse(
        List<String> updatedRefs, int receivedObjects, List<String> skippedBranches) {
}
