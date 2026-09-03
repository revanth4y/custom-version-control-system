package com.gitforge.vcs.insights;

import com.gitforge.vcs.object.ObjectId;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * How far back a repository's history actually goes.
 *
 * <p><strong>Measured from commits, never from the repository record.</strong>
 * A repository row carries a creation time, and using it here would be easy and
 * wrong: history can be pushed in from somewhere older, and a repository created
 * this morning can hold a decade of work. The commits are the history, so the
 * commits are what this reads.
 *
 * <p>The span is earliest authored commit to latest authored commit, both
 * inclusive. Author time rather than commit time, matching every other date
 * figure here — it is when the work was done rather than when it was last
 * rewritten.
 *
 * @param earliest the oldest authored commit
 * @param latest the newest authored commit
 * @param earliestCommit which commit that was
 * @param latestCommit which commit that was
 */
public record HistorySpan(
        Instant earliest, Instant latest, ObjectId earliestCommit, ObjectId latestCommit) {

    /**
     * The span of a set of commit facts, or empty when there are none.
     *
     * <p>Empty for an empty repository, which is a real answer rather than a
     * failure: a repository with no commits has no history to span, and reporting
     * zero-length would invent a moment that never happened.
     */
    public static Optional<HistorySpan> of(Collection<CommitInsights.Fact> facts) {
        if (facts == null || facts.isEmpty()) {
            return Optional.empty();
        }

        CommitInsights.Fact first = facts.stream()
                .min(Comparator.comparing(CommitInsights.Fact::timestamp)
                        .thenComparing(fact -> fact.id().toHex()))
                .orElseThrow();
        CommitInsights.Fact last = facts.stream()
                .max(Comparator.comparing(CommitInsights.Fact::timestamp)
                        .thenComparing(fact -> fact.id().toHex()))
                .orElseThrow();

        return Optional.of(new HistorySpan(
                first.timestamp(), last.timestamp(), first.id(), last.id()));
    }

    /**
     * How long the history covers.
     *
     * <p>Zero for a single commit, which is the truth: one commit spans no time.
     */
    public Duration duration() {
        return Duration.between(earliest, latest);
    }

    /** Whether every commit was authored at the same instant. */
    public boolean instantaneous() {
        return earliest.equals(latest);
    }
}
