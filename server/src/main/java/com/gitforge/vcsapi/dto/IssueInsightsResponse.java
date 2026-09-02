package com.gitforge.vcsapi.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Issue activity over a window, and the totals behind it.
 *
 * <p><strong>{@code closedUndated} is the honest part.</strong> Issues closed
 * before closure times were recorded have no date, so they are counted in
 * {@code closed} and cannot appear in {@code closedSeries}. Reporting them as
 * closed on some invented day would put a date into these figures that never
 * happened; leaving them out of the total would understate what is closed. They
 * are counted once and named separately.
 *
 * @param closedSeries closures with a known date, bucketed; its total may be
 *     less than {@code closedInRange} is not — the two are reconciled by
 *     {@code closedUndated}
 */
public record IssueInsightsResponse(
        LocalDate from,
        LocalDate to,
        int total,
        int open,
        int closed,
        int closedUndated,
        int openedInRange,
        int closedInRange,
        InsightsSeriesResponse openedSeries,
        InsightsSeriesResponse closedSeries) {
}
