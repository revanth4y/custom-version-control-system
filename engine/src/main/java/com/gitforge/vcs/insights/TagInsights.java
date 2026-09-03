package com.gitforge.vcs.insights;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.ref.TagService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What a repository's tags look like as a set.
 *
 * <p>Reads only. Nothing here creates, moves or deletes a tag, and the peeling it
 * does is {@link TagService}'s rather than its own, so a chain resolves here
 * exactly as it resolves everywhere else — same rule, same ceiling.
 *
 * <p><strong>Cadence is only reported for annotated tags</strong>, and only when
 * there are at least two. A lightweight tag carries no time of its own — it is a
 * file holding an id — so the only date available for one is its target's, which
 * says when the code was written rather than when it was tagged. Reporting that
 * as cadence would be answering a different question quietly, so lightweight tags
 * are counted and excluded from the timing.
 */
public final class TagInsights {

    private final TagService tags;

    public TagInsights(TagService tags) {
        if (tags == null) {
            throw new IllegalArgumentException("Tag insights need a tag service");
        }
        this.tags = tags;
    }

    /**
     * @param name the tag
     * @param annotated whether a tag object stands behind the ref
     * @param taggedAt when the annotation was written, absent for a lightweight tag
     * @param target what the ref holds
     * @param commit what it ultimately names, absent when the chain cannot be followed
     */
    public record TagFact(
            String name,
            boolean annotated,
            Optional<Instant> taggedAt,
            ObjectId target,
            Optional<ObjectId> commit) {
    }

    /**
     * @param medianInterval the middle gap between consecutive annotated tags,
     *     absent with fewer than two. A median rather than a mean because one
     *     long quiet period should not redefine the typical gap
     */
    public record Summary(
            int total,
            int annotated,
            int lightweight,
            List<TagFact> facts,
            Optional<Duration> medianInterval,
            Optional<Instant> firstTagged,
            Optional<Instant> lastTagged) {
    }

    public Summary summarise() {
        List<TagFact> facts = new ArrayList<>();

        for (String name : tags.listTags()) {
            Optional<ObjectId> target = quietly(() -> tags.getTag(name));
            if (target.isEmpty()) {
                continue;
            }
            Optional<Tag> annotation = quietly(() -> tags.annotationOf(name));
            Optional<ObjectId> commit = quietly(() -> tags.peel(name));

            facts.add(new TagFact(
                    name,
                    annotation.isPresent(),
                    annotation.map(tag -> tag.tagger().timestamp()),
                    target.get(),
                    commit));
        }

        List<Instant> taggedAt = facts.stream()
                .map(TagFact::taggedAt)
                .flatMap(Optional::stream)
                .sorted()
                .toList();

        int annotated = (int) facts.stream().filter(TagFact::annotated).count();

        return new Summary(
                facts.size(),
                annotated,
                facts.size() - annotated,
                List.copyOf(facts),
                medianInterval(taggedAt),
                taggedAt.isEmpty() ? Optional.empty() : Optional.of(taggedAt.get(0)),
                taggedAt.isEmpty() ? Optional.empty() : Optional.of(taggedAt.get(taggedAt.size() - 1)));
    }

    private static Optional<Duration> medianInterval(List<Instant> sorted) {
        if (sorted.size() < 2) {
            return Optional.empty();
        }
        List<Duration> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            gaps.add(Duration.between(sorted.get(i - 1), sorted.get(i)));
        }
        gaps.sort(Comparator.naturalOrder());

        int middle = gaps.size() / 2;
        if (gaps.size() % 2 == 1) {
            return Optional.of(gaps.get(middle));
        }
        return Optional.of(gaps.get(middle - 1).plus(gaps.get(middle)).dividedBy(2));
    }

    /** A tag that cannot be read is skipped rather than taking the whole summary down. */
    private static <T> Optional<T> quietly(java.util.function.Supplier<Optional<T>> read) {
        try {
            return read.get();
        } catch (RefException ex) {
            return Optional.empty();
        }
    }
}
