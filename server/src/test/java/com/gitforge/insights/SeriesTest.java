package com.gitforge.insights;

import com.gitforge.common.error.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gap-filled counts over time.
 *
 * <p>The property worth protecting is that a quiet day is a zero rather than an
 * absence. A series with a hole where a zero belongs makes every caller invent
 * its own filling, and a chart that skips empty weeks says something untrue
 * about the repository it describes.
 */
class SeriesTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static DateRange range(String from, String to) {
        return DateRange.resolve(d(from), d(to));
    }

    @Nested
    @DisplayName("gap filling")
    class GapFilling {

        @Test
        void everyDayInTheRangeIsPresentEvenWithNoActivity() {
            List<Series.Point> points =
                    Series.of(range("2026-01-01", "2026-01-05"), TimeBucket.DAY, Map.of());

            assertThat(points).hasSize(5);
            assertThat(points).allMatch(p -> p.count() == 0);
            assertThat(points.get(0).date()).isEqualTo(d("2026-01-01"));
            assertThat(points.get(4).date()).isEqualTo(d("2026-01-05"));
        }

        @Test
        void quietDaysBetweenBusyOnesAreZeroesNotGaps() {
            List<Series.Point> points = Series.of(
                    range("2026-01-01", "2026-01-05"),
                    TimeBucket.DAY,
                    Map.of(d("2026-01-01"), 2, d("2026-01-05"), 3));

            assertThat(points).extracting(Series.Point::count).containsExactly(2, 0, 0, 0, 3);
        }

        @Test
        void anEmptyRepositoryProducesAFullSeriesOfZeroes() {
            List<Series.Point> points =
                    Series.of(range("2026-01-01", "2026-01-31"), TimeBucket.DAY, Map.of());

            assertThat(points).hasSize(31);
            assertThat(Series.total(points)).isZero();
        }
    }

    @Nested
    @DisplayName("range discipline")
    class RangeDiscipline {

        @Test
        void countsOutsideTheRangeAreIgnoredNotFoldedIntoAnEdge() {
            List<Series.Point> points = Series.of(
                    range("2026-01-10", "2026-01-12"),
                    TimeBucket.DAY,
                    Map.of(
                            d("2026-01-09"), 100,
                            d("2026-01-10"), 1,
                            d("2026-01-13"), 100));

            assertThat(Series.total(points)).isEqualTo(1);
            assertThat(points).extracting(Series.Point::count).containsExactly(1, 0, 0);
        }

        @Test
        void theTotalEqualsTheSumOfCountsInsideTheRange() {
            Map<LocalDate, Integer> counts = Map.of(
                    d("2026-01-01"), 4, d("2026-01-02"), 6, d("2026-01-03"), 1);

            List<Series.Point> points = Series.of(range("2026-01-01", "2026-01-31"), TimeBucket.DAY, counts);

            assertThat(Series.total(points)).isEqualTo(11);
        }

        @Test
        void aSingleDayRangeProducesASinglePoint() {
            List<Series.Point> points = Series.of(
                    range("2026-01-01", "2026-01-01"), TimeBucket.DAY, Map.of(d("2026-01-01"), 7));

            assertThat(points).hasSize(1);
            assertThat(points.get(0).count()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("weekly buckets")
    class Weekly {

        @Test
        void aWeekIsLabelledByTheMondayItStartsOn() {
            // 2026-01-01 is a Thursday; its week starts Monday 2025-12-29.
            assertThat(d("2026-01-01").getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);

            List<Series.Point> points = Series.of(
                    range("2026-01-01", "2026-01-07"), TimeBucket.WEEK, Map.of(d("2026-01-01"), 3));

            assertThat(points.get(0).date()).isEqualTo(d("2025-12-29"));
            assertThat(points.get(0).date().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        }

        @Test
        void daysInOneWeekAreSummedIntoOneBucket() {
            List<Series.Point> points = Series.of(
                    range("2026-01-05", "2026-01-11"),
                    TimeBucket.WEEK,
                    Map.of(d("2026-01-05"), 1, d("2026-01-07"), 2, d("2026-01-11"), 3));

            // 2026-01-05 is a Monday, so the whole range is one week.
            assertThat(points).hasSize(1);
            assertThat(points.get(0).count()).isEqualTo(6);
        }

        @Test
        void quietWeeksArePresentAsZeroes() {
            List<Series.Point> points = Series.of(
                    range("2026-01-05", "2026-02-01"),
                    TimeBucket.WEEK,
                    Map.of(d("2026-01-05"), 1));

            assertThat(points).hasSize(4);
            assertThat(points).extracting(Series.Point::count).containsExactly(1, 0, 0, 0);
        }

        @Test
        void aWeeklyTotalStillEqualsTheDailyTotal() {
            Map<LocalDate, Integer> counts = Map.of(
                    d("2026-01-05"), 2, d("2026-01-09"), 3, d("2026-01-20"), 5);

            DateRange range = range("2026-01-01", "2026-01-31");

            assertThat(Series.total(Series.of(range, TimeBucket.WEEK, counts)))
                    .isEqualTo(Series.total(Series.of(range, TimeBucket.DAY, counts)))
                    .isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("bucket parsing")
    class Parsing {

        @Test
        void absentMeansDay() {
            assertThat(TimeBucket.parse(null)).isEqualTo(TimeBucket.DAY);
            assertThat(TimeBucket.parse("  ")).isEqualTo(TimeBucket.DAY);
        }

        @Test
        void namesAreCaseInsensitive() {
            assertThat(TimeBucket.parse("week")).isEqualTo(TimeBucket.WEEK);
            assertThat(TimeBucket.parse("WEEK")).isEqualTo(TimeBucket.WEEK);
            assertThat(TimeBucket.parse(" Day ")).isEqualTo(TimeBucket.DAY);
        }

        @Test
        void anUnknownBucketIsRefused() {
            assertThatThrownBy(() -> TimeBucket.parse("fortnight"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Use day or week");
        }
    }
}
