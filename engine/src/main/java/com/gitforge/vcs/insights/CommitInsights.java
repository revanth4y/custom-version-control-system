package com.gitforge.vcs.insights;

import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.storage.ObjectStore;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a set of commits adds up to.
 *
 * <p>Reads each commit once and answers every question from the result, because
 * the alternative — a pass per figure — reads the same objects three times to
 * produce numbers that must agree. Reading once is also what makes them agree by
 * construction rather than by care.
 *
 * <p><strong>Identity is the email.</strong> A person commits under several
 * display names over the years; the address is what identifies them. The name
 * shown is the one from the first commit seen for that address, which is stable
 * for a given history rather than a popularity contest between spellings.
 *
 * <p><strong>Days are UTC.</strong> A commit carries its author's offset, so
 * bucketing by anything else would put the same commit on different days for
 * different readers.
 *
 * <p>Deliberately outside Spring, and given ids rather than a way to find them,
 * so the aggregation can be tested without an application context and without an
 * opinion about which commits should be counted — that is the caller's business,
 * and getting it wrong is what {@code ReferenceRoots} exists to prevent.
 */
public final class CommitInsights {

    private final ObjectStore objects;

    public CommitInsights(ObjectStore objects) {
        if (objects == null) {
            throw new IllegalArgumentException("Commit insights need an object store");
        }
        this.objects = objects;
    }

    /**
     * One commit, reduced to the facts every figure is built from.
     *
     * @param merge whether the commit has more than one parent
     */
    public record Fact(
            ObjectId id,
            String authorName,
            String authorEmail,
            java.time.Instant timestamp,
            LocalDate day,
            boolean merge,
            int parents) {
    }

    /** An author, and how much of this history is theirs. */
    public record Contributor(String name, String email, int commits, int merges,
                              LocalDate firstCommit, LocalDate lastCommit) {
    }

    /** Everything derived from one read of the given commits. */
    public record Summary(
            int commits,
            int merges,
            List<Fact> facts,
            List<Contributor> contributors,
            Map<LocalDate, Integer> countsByDay) {

        /** Commits that are not merges — the ordinary ones. */
        public int nonMerges() {
            return commits - merges;
        }

        /** The earliest authored day, or empty for no commits. */
        public java.util.Optional<LocalDate> firstDay() {
            return countsByDay.keySet().stream().findFirst();
        }

        /** The latest authored day, or empty for no commits. */
        public java.util.Optional<LocalDate> lastDay() {
            return countsByDay.isEmpty()
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(((TreeMap<LocalDate, Integer>) countsByDay).lastKey());
        }
    }

    /**
     * Reads every given commit and summarises it.
     *
     * <p>A commit that cannot be read is skipped rather than fatal, matching how
     * statistics treat an unreadable root: describing most of a repository is
     * more useful than refusing to describe any of it because one object is
     * damaged.
     */
    public Summary summarise(Collection<ObjectId> commits) {
        List<Fact> facts = new ArrayList<>();
        Map<String, Contributor> byEmail = new LinkedHashMap<>();
        Map<LocalDate, Integer> byDay = new TreeMap<>();
        int merges = 0;

        for (ObjectId id : commits) {
            Commit commit;
            try {
                commit = objects.readCommit(id);
            } catch (CorruptObjectException ex) {
                continue;
            }

            LocalDate day = commit.author().timestamp().atZone(ZoneOffset.UTC).toLocalDate();
            boolean merge = commit.isMerge();
            if (merge) {
                merges++;
            }

            facts.add(new Fact(
                    id,
                    commit.author().name(),
                    commit.author().email(),
                    commit.author().timestamp(),
                    day,
                    merge,
                    commit.parents().size()));

            byDay.merge(day, 1, Integer::sum);
            accumulate(byEmail, commit.author().name(), commit.author().email(), day, merge);
        }

        List<Contributor> contributors = new ArrayList<>(byEmail.values());
        contributors.sort(Comparator
                .comparingInt(Contributor::commits).reversed()
                .thenComparing(Contributor::email));

        return new Summary(facts.size(), merges, List.copyOf(facts), List.copyOf(contributors), byDay);
    }

    private static void accumulate(
            Map<String, Contributor> byEmail, String name, String email, LocalDate day, boolean merge) {

        byEmail.merge(
                email,
                new Contributor(name, email, 1, merge ? 1 : 0, day, day),
                (existing, added) -> new Contributor(
                        // The first name seen for an address wins, so the label is
                        // stable for a history rather than depending on read order
                        // between equally-common spellings.
                        existing.name(),
                        existing.email(),
                        existing.commits() + 1,
                        existing.merges() + added.merges(),
                        existing.firstCommit().isBefore(added.firstCommit())
                                ? existing.firstCommit() : added.firstCommit(),
                        existing.lastCommit().isAfter(added.lastCommit())
                                ? existing.lastCommit() : added.lastCommit()));
    }
}
