package com.gitforge.insights;

import com.gitforge.common.error.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The window every Insights figure is counted over.
 *
 * <p>The boundary cases carry the weight here. An off-by-one at either end is
 * the kind of defect that produces a plausible number nobody questions, so both
 * ends being inclusive is asserted rather than assumed.
 */
class DateRangeTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Nested
    @DisplayName("inclusive boundaries")
    class Boundaries {

        @Test
        void aSingleDayIsALegalRange() {
            DateRange range = DateRange.resolve(d("2026-01-01"), d("2026-01-01"));

            assertThat(range.days()).isEqualTo(1);
            assertThat(range.eachDay()).containsExactly(d("2026-01-01"));
        }

        @Test
        void bothEndsAreIncluded() {
            DateRange range = DateRange.resolve(d("2026-01-01"), d("2026-01-03"));

            assertThat(range.days()).isEqualTo(3);
            assertThat(range.eachDay())
                    .containsExactly(d("2026-01-01"), d("2026-01-02"), d("2026-01-03"));
        }

        @Test
        void containsIsInclusiveAtBothEnds() {
            DateRange range = DateRange.resolve(d("2026-01-10"), d("2026-01-20"));

            assertThat(range.contains(d("2026-01-10"))).isTrue();
            assertThat(range.contains(d("2026-01-20"))).isTrue();
            assertThat(range.contains(d("2026-01-09"))).isFalse();
            assertThat(range.contains(d("2026-01-21"))).isFalse();
            assertThat(range.contains(null)).isFalse();
        }

        @Test
        void aRangeSpanningALeapDayCountsIt() {
            DateRange range = DateRange.resolve(d("2028-02-28"), d("2028-03-01"));

            assertThat(range.eachDay()).contains(d("2028-02-29"));
            assertThat(range.days()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        void anAbsentEndIsTodayInUtc() {
            DateRange range = DateRange.resolve(null, null);

            assertThat(range.to()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        }

        @Test
        void anAbsentStartIsAYearOfInclusiveDays() {
            DateRange range = DateRange.resolve(null, d("2026-12-31"));

            assertThat(range.days()).isEqualTo(DateRange.DEFAULT_DAYS);
            assertThat(range.from()).isEqualTo(d("2026-01-01"));
        }

        @Test
        void supplyingOnlyAStartEndsToday() {
            DateRange range = DateRange.resolve(LocalDate.now(ZoneOffset.UTC).minusDays(2), null);

            assertThat(range.to()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
            assertThat(range.days()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        void anInvertedRangeIsRefused() {
            assertThatThrownBy(() -> DateRange.resolve(d("2026-02-01"), d("2026-01-01")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("must not be after");
        }

        @Test
        void theMaximumSpanIsAllowed() {
            // MAX_DAYS counted inclusively: 366 days is the largest legal window.
            DateRange range = DateRange.resolve(d("2026-01-01"), d("2026-01-01").plusDays(365));

            assertThat(range.days()).isEqualTo(DateRange.MAX_DAYS);
        }

        @Test
        void oneDayBeyondTheMaximumIsRefused() {
            assertThatThrownBy(() ->
                    DateRange.resolve(d("2026-01-01"), d("2026-01-01").plusDays(366)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("at most 366");
        }

        @Test
        void anExcessiveRangeIsRefused() {
            assertThatThrownBy(() -> DateRange.resolve(d("2000-01-01"), d("2026-01-01")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("at most");
        }

        @Test
        void aNullEndOfAConstructedRangeIsRejected() {
            assertThatThrownBy(() -> new DateRange(d("2026-01-01"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both ends");
        }
    }
}
