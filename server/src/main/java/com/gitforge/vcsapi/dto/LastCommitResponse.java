package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.object.Commit;

import java.time.Instant;

/**
 * The commit that last touched a path, as a file listing shows it.
 *
 * <p>Deliberately smaller than {@link CommitSummaryResponse}: a listing row has
 * space for a subject and a timestamp, and sending the parents, tree and email
 * for every entry would multiply the size of a directory response for data
 * nothing on that screen displays.
 *
 * @param shortSha the abbreviated id, for display
 */
public record LastCommitResponse(
        String sha,
        String shortSha,
        String message,
        String authorName,
        Instant timestamp) {

    public static LastCommitResponse from(Commit commit) {
        return new LastCommitResponse(
                commit.id().toHex(),
                commit.id().abbreviate(7),
                commit.message(),
                commit.author().name(),
                commit.author().timestamp());
    }
}
