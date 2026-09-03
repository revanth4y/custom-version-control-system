package com.gitforge.vcsapi.dto;

import com.gitforge.insights.Series;

import java.time.LocalDate;
import java.util.List;

/**
 * Counts over time, with every bucket present.
 *
 * <p>Quiet buckets are zeroes rather than omissions, so a client renders a chart
 * without having to work out which periods were left out.
 *
 * @param bucket the grain, {@code day} or {@code week}
 * @param points one entry per bucket, oldest first, labelled by the day the
 *     bucket starts on
 */
public record InsightsSeriesResponse(
        LocalDate from, LocalDate to, String bucket, int total, List<Point> points) {

    public record Point(LocalDate date, int count) {
    }

    public static InsightsSeriesResponse of(
            LocalDate from, LocalDate to, String bucket, List<Series.Point> points) {

        return new InsightsSeriesResponse(
                from,
                to,
                bucket,
                Series.total(points),
                points.stream().map(p -> new Point(p.date(), p.count())).toList());
    }
}
