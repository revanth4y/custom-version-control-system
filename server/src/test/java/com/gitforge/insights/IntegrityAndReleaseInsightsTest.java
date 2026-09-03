package com.gitforge.insights;

import com.gitforge.release.Release;
import com.gitforge.vcsapi.dto.IntegrityReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integrity indicator and release analytics.
 *
 * <p>The indicator tests exist to stop three different kinds of "we do not know"
 * from being reported as "healthy". That reduction is the easy mistake, and it
 * is the one that turns a health display into a reassurance nobody earned.
 */
class IntegrityAndReleaseInsightsTest {

    private static IntegrityReport report(
            long stored, int verified, List<IntegrityReport.DamagedObject> damaged,
            Boolean healthy, boolean truncated) {

        return new IntegrityReport(
                stored, verified, damaged, healthy, truncated, Instant.EPOCH, 1L);
    }

    private static IntegrityReport.DamagedObject damagedObject() {
        return new IntegrityReport.DamagedObject(
                "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3",
                IntegrityReport.Reason.HASH_MISMATCH,
                "detail");
    }

    @Nested
    @DisplayName("integrity indicator keeps four states apart")
    class Integrity {

        @Test
        void aCompleteCleanScanIsHealthy() {
            assertThat(IntegrityIndicator.of(report(10, 10, List.of(), true, false)))
                    .isEqualTo(IntegrityIndicator.HEALTHY);
        }

        @Test
        void aDamagedObjectIsDamaged() {
            assertThat(IntegrityIndicator.of(
                    report(10, 10, List.of(damagedObject()), false, false)))
                    .isEqualTo(IntegrityIndicator.DAMAGED);
        }

        @Test
        void damageWinsOverTruncation() {
            // A scan that found damage has established damage, however much it
            // failed to reach.
            assertThat(IntegrityIndicator.of(
                    report(10_000, 10, List.of(damagedObject()), false, true)))
                    .isEqualTo(IntegrityIndicator.DAMAGED);
        }

        @Test
        void aCleanButTruncatedScanIsNotHealthy() {
            assertThat(IntegrityIndicator.of(report(10_000, 10, List.of(), true, true)))
                    .isEqualTo(IntegrityIndicator.TRUNCATED);
        }

        @Test
        void anEmptyRepositoryIsNotVerifiedRatherThanHealthy() {
            // Nothing was checked, so nothing was established.
            assertThat(IntegrityIndicator.of(report(0, 0, List.of(), null, false)))
                    .isEqualTo(IntegrityIndicator.NOT_VERIFIED);
        }

        @Test
        void aNullReportIsNotVerified() {
            assertThat(IntegrityIndicator.of(null)).isEqualTo(IntegrityIndicator.NOT_VERIFIED);
        }

        @Test
        void onlyHealthyClaimsSoundness() {
            assertThat(IntegrityIndicator.HEALTHY.soundnessEstablished()).isTrue();
            assertThat(IntegrityIndicator.DAMAGED.soundnessEstablished()).isFalse();
            assertThat(IntegrityIndicator.TRUNCATED.soundnessEstablished()).isFalse();
            assertThat(IntegrityIndicator.NOT_VERIFIED.soundnessEstablished()).isFalse();
        }
    }

    @Nested
    @DisplayName("release analytics")
    class Releases {

        private Release release(String tag, boolean draft, boolean prerelease, Instant published) {
            Release release = new Release(null, null, tag, "Version " + tag, "notes", draft, prerelease);
            if (!draft && published != null) {
                setPublishedAt(release, published);
            }
            return release;
        }

        /** Reflection because publishedAt is stamped by the entity, not settable. */
        private void setPublishedAt(Release release, Instant when) {
            try {
                var field = Release.class.getDeclaredField("publishedAt");
                field.setAccessible(true);
                field.set(release, when);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Test
        void noReleasesSummariseToZero() {
            ReleaseInsights.Summary summary = ReleaseInsights.summarise(List.of(), Set.of());

            assertThat(summary.total()).isZero();
            assertThat(summary.published()).isZero();
            assertThat(summary.drafts()).isZero();
            assertThat(summary.medianInterval()).isEmpty();
            assertThat(summary.firstPublished()).isEmpty();
        }

        @Test
        void publishedAndDraftsSumToTheTotal() {
            var releases = List.of(
                    release("v1", false, false, Instant.parse("2026-01-01T00:00:00Z")),
                    release("v2", true, false, null),
                    release("v3", false, true, Instant.parse("2026-02-01T00:00:00Z")));

            ReleaseInsights.Summary summary = ReleaseInsights.summarise(releases, Set.of());

            assertThat(summary.total()).isEqualTo(3);
            assertThat(summary.published()).isEqualTo(2);
            assertThat(summary.drafts()).isEqualTo(1);
            assertThat(summary.published() + summary.drafts()).isEqualTo(summary.total());
        }

        @Test
        void prereleasesAreCountedWhetherPublishedOrNot() {
            var releases = List.of(
                    release("v1", false, true, Instant.parse("2026-01-01T00:00:00Z")),
                    release("v2", true, true, null));

            assertThat(ReleaseInsights.summarise(releases, Set.of()).prereleases()).isEqualTo(2);
        }

        @Test
        void aReleaseWhoseTagIsGoneIsReportedAsSuch() {
            var releases = List.of(
                    release("v1", false, false, Instant.parse("2026-01-01T00:00:00Z")),
                    release("v2", false, false, Instant.parse("2026-02-01T00:00:00Z")));

            ReleaseInsights.Summary summary = ReleaseInsights.summarise(releases, Set.of("v1"));

            assertThat(summary.withExistingTag()).isEqualTo(1);
            assertThat(summary.withMissingTag()).isEqualTo(1);
            assertThat(summary.withExistingTag() + summary.withMissingTag())
                    .isEqualTo(summary.total());
        }

        @Test
        void cadenceIsMeasuredBetweenPublicationsOnly() {
            var releases = List.of(
                    release("v1", false, false, Instant.parse("2026-01-01T00:00:00Z")),
                    release("v2", true, false, null),
                    release("v3", false, false, Instant.parse("2026-01-11T00:00:00Z")));

            ReleaseInsights.Summary summary = ReleaseInsights.summarise(releases, Set.of());

            // The draft has no publication date and must not invent a rhythm.
            assertThat(summary.medianInterval()).contains(Duration.ofDays(10));
            assertThat(summary.firstPublished()).contains(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(summary.lastPublished()).contains(Instant.parse("2026-01-11T00:00:00Z"));
        }

        @Test
        void cadenceNeedsTwoPublications() {
            var releases = List.of(release("v1", false, false, Instant.parse("2026-01-01T00:00:00Z")));

            assertThat(ReleaseInsights.summarise(releases, Set.of()).medianInterval()).isEmpty();
        }

        @Test
        void aViewerWhoSeesNoDraftsSeesNoDraftCount() {
            // Drafts are filtered before they reach here, so the count is zero by
            // construction rather than by a rule this class could get wrong.
            var published = List.of(release("v1", false, false, Instant.parse("2026-01-01T00:00:00Z")));

            assertThat(ReleaseInsights.summarise(published, Set.of()).drafts()).isZero();
        }

        @Test
        void tagsWithoutReleasesAreListedSorted() {
            var releases = List.of(release("v2", false, false, Instant.parse("2026-01-01T00:00:00Z")));

            assertThat(ReleaseInsights.tagsWithoutReleases(List.of("v3", "v1", "v2"), releases))
                    .containsExactly("v1", "v3");
        }

        @Test
        void everyTagHavingAReleaseLeavesNoneOutstanding() {
            var releases = List.of(release("v1", false, false, Instant.parse("2026-01-01T00:00:00Z")));

            assertThat(ReleaseInsights.tagsWithoutReleases(List.of("v1"), releases)).isEmpty();
        }
    }
}
