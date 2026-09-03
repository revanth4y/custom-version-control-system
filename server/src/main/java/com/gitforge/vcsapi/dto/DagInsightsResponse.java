package com.gitforge.vcsapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * The shape of the commit graph, and how far back it runs.
 *
 * <p>Counted from the same roots garbage collection uses — branches, HEAD,
 * remote-tracking refs, tags and the work tree — so these figures describe the
 * history the repository will actually keep.
 *
 * @param mergeRatio merges as a fraction of commits; zero for an empty
 *     repository rather than a division nobody can perform
 * @param maxDepth the longest chain of parents, counting the commit itself
 * @param maxParents the widest merge; two for an ordinary one
 * @param earliestCommit start of the history span, null when there is none
 */
public record DagInsightsResponse(
        int commits,
        int merges,
        int nonMerges,
        double mergeRatio,
        int roots,
        List<String> rootCommits,
        int maxDepth,
        int maxParents,
        Instant earliestCommit,
        Instant latestCommit,
        Long historyDurationSeconds) {
}
