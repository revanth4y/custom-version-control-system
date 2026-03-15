package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.object.Commit;

import java.time.Instant;

/**
 * The commit a branch points at, as shown beside the branch name.
 *
 * <p>Deliberately narrower than {@link CommitSummaryResponse}: a branch listing
 * shows one line per branch, and the tree and parent ids that a history listing
 * needs would be carried for every branch and used for none of them.
 *
 * @param message the full commit message; the caller shows its first line
 */
public record BranchTipResponse(
        String sha,
        String shortSha,
        String message,
        String authorName,
        Instant timestamp) {

    public static BranchTipResponse from(Commit commit) {
        return new BranchTipResponse(
                commit.id().toHex(),
                commit.id().abbreviate(7),
                commit.message(),
                commit.author().name(),
                commit.author().timestamp());
    }
}
