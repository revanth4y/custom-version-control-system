package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.object.Commit;

import java.time.Instant;
import java.util.List;

/**
 * A commit as shown in a history listing.
 *
 * @param shortSha the abbreviated id used for display
 * @param parents parent ids in their significant order; the first is the branch
 *     the commit continues
 */
public record CommitSummaryResponse(
        String sha,
        String shortSha,
        String message,
        String authorName,
        String authorEmail,
        Instant timestamp,
        List<String> parents,
        String tree,
        boolean merge) {

    public static CommitSummaryResponse from(Commit commit) {
        return new CommitSummaryResponse(
                commit.id().toHex(),
                commit.id().abbreviate(7),
                commit.message(),
                commit.author().name(),
                commit.author().email(),
                commit.author().timestamp(),
                commit.parents().stream().map(parent -> parent.toHex()).toList(),
                commit.tree().toHex(),
                commit.isMerge());
    }
}
