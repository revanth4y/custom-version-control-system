package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.object.Commit;

import java.time.Instant;
import java.util.List;

/**
 * A commit as shown in a history listing.
 *
 * <p>Both signatures are reported. The stored commit carries an author and a
 * committer separately — it is two lines of the serialized form, and the engine
 * lets them differ — so sending only one would drop something the object
 * actually holds. Through this API they are currently written the same, which is
 * a fact about how commits are created here rather than about the format, and is
 * for the reader to notice rather than for this to decide by omitting a field.
 *
 * @param shortSha the abbreviated id used for display
 * @param parents parent ids in their significant order; the first is the branch
 *     the commit continues
 * @param committerName who recorded the commit, which is not always who wrote it
 * @param committerTimestamp when it was recorded, which is not always when it
 *     was written
 */
public record CommitSummaryResponse(
        String sha,
        String shortSha,
        String message,
        String authorName,
        String authorEmail,
        Instant timestamp,
        String committerName,
        String committerEmail,
        Instant committerTimestamp,
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
                commit.committer().name(),
                commit.committer().email(),
                commit.committer().timestamp(),
                commit.parents().stream().map(parent -> parent.toHex()).toList(),
                commit.tree().toHex(),
                commit.isMerge());
    }
}
