package com.gitforge.insights;

import com.gitforge.release.Release;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a repository's releases look like as a set.
 *
 * <p><strong>Given the releases rather than a way to fetch them.</strong>
 * Visibility is decided once, by the layer that knows who is asking, and passing
 * the already-filtered list in means this class cannot leak a draft by
 * forgetting a check. A draft never reaches here for a viewer who may not see
 * one, so there is no rule here that could be got wrong.
 *
 * <p><strong>Cadence is measured between published releases only.</strong> A
 * draft has no publication date — that is what makes it a draft — and treating
 * its creation time as a release date would report a rhythm that never happened.
 */
public final class ReleaseInsights {

    private ReleaseInsights() {
    }

    /**
     * @param total every release the viewer may see
     * @param published releases that have gone out
     * @param drafts releases still unpublished; always zero for a viewer who may
     *     not see drafts, because none were passed in
     * @param prereleases published or not, releases marked as a pre-release
     * @param withExistingTag releases whose tag is still present in the repository
     * @param medianInterval the middle gap between consecutive publications,
     *     absent with fewer than two. A median rather than a mean, so one long
     *     pause does not redefine the typical gap
     */
    public record Summary(
            int total,
            int published,
            int drafts,
            int prereleases,
            int withExistingTag,
            int withMissingTag,
            Optional<Duration> medianInterval,
            Optional<Instant> firstPublished,
            Optional<Instant> lastPublished) {
    }

    /**
     * Summarises releases already filtered for the viewer.
     *
     * @param existingTags the tags the repository currently holds, so a release
     *     whose tag has since been deleted is reported rather than assumed intact
     */
    public static Summary summarise(Collection<Release> releases, Set<String> existingTags) {
        if (releases == null || releases.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0, 0,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        int drafts = 0;
        int prereleases = 0;
        int withTag = 0;
        List<Instant> publications = new ArrayList<>();

        for (Release release : releases) {
            if (release.isDraft()) {
                drafts++;
            }
            if (release.isPrerelease()) {
                prereleases++;
            }
            if (existingTags != null && existingTags.contains(release.getTagName())) {
                withTag++;
            }
            if (!release.isDraft() && release.getPublishedAt() != null) {
                publications.add(release.getPublishedAt());
            }
        }

        publications.sort(Comparator.naturalOrder());

        return new Summary(
                releases.size(),
                releases.size() - drafts,
                drafts,
                prereleases,
                withTag,
                releases.size() - withTag,
                medianInterval(publications),
                publications.isEmpty() ? Optional.empty() : Optional.of(publications.get(0)),
                publications.isEmpty()
                        ? Optional.empty()
                        : Optional.of(publications.get(publications.size() - 1)));
    }

    /** Tags that exist and have no release against them. */
    public static List<String> tagsWithoutReleases(
            Collection<String> existingTags, Collection<Release> releases) {

        Set<String> released = releases == null
                ? Set.of()
                : releases.stream().map(Release::getTagName).collect(java.util.stream.Collectors.toSet());

        return existingTags == null
                ? List.of()
                : existingTags.stream().filter(tag -> !released.contains(tag)).sorted().toList();
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
}
