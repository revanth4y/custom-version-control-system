package com.gitforge.insights;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts over time, with no holes in them.
 *
 * <p><strong>Every bucket in the range is present, including the empty ones.</strong>
 * That is the whole point of this class. A series that omitted quiet periods
 * would make a caller reconstruct them, and every caller would reconstruct them
 * slightly differently — a chart with a gap where a zero belongs is a chart that
 * says something untrue about the repository.
 *
 * <p>The last bucket of a weekly series may extend past the end of the range, and
 * is kept: it holds real counts for the days that do fall inside. It is labelled
 * by the day it starts on, so a caller can see for itself that the week is
 * partial rather than being told a smaller number without explanation.
 */
public final class Series {

    private Series() {
    }

    /** One bucket: the day it starts on, and what fell in it. */
    public record Point(LocalDate date, int count) {
    }

    /**
     * A gap-filled series over {@code range}, at the grain {@code bucket} names.
     *
     * @param counts counts keyed by the exact day something happened; days outside
     *     the range are ignored rather than folded into an edge bucket, because a
     *     range means what it says
     */
    public static List<Point> of(DateRange range, TimeBucket bucket, Map<LocalDate, Integer> counts) {
        Map<LocalDate, Integer> byBucket = new TreeMap<>();

        for (Map.Entry<LocalDate, Integer> entry : counts.entrySet()) {
            if (range.contains(entry.getKey())) {
                byBucket.merge(bucket.startOf(entry.getKey()), entry.getValue(), Integer::sum);
            }
        }

        List<Point> points = new ArrayList<>();
        LocalDate cursor = bucket.startOf(range.from());
        while (!cursor.isAfter(range.to())) {
            points.add(new Point(cursor, byBucket.getOrDefault(cursor, 0)));
            cursor = bucket.next(cursor);
        }
        return List.copyOf(points);
    }

    /** The sum of a series, which must equal the total counted inside the range. */
    public static int total(List<Point> points) {
        return points.stream().mapToInt(Point::count).sum();
    }
}
