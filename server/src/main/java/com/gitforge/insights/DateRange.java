package com.gitforge.insights;

import com.gitforge.common.error.BadRequestException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * A closed window of days, in UTC.
 *
 * <p><strong>Both ends are inclusive.</strong> A range of one day is a legal
 * range, and asking for {@code 2026-01-01} to {@code 2026-01-01} returns that
 * day rather than nothing — which is the answer a person means when they pick
 * a single date.
 *
 * <p>UTC throughout, deliberately and without an option to change it. Commit
 * timestamps carry their own offsets, so bucketing them by anything other than a
 * fixed zone would make the same commit land on different days depending on who
 * asked, and two people comparing figures would both be right.
 *
 * <p>The defaults and the ceiling are the ones contribution counting already
 * used, and this class is now where they live so there is one definition rather
 * than one per caller.
 */
public record DateRange(LocalDate from, LocalDate to) {

    /** A year of history, which is what a contribution calendar shows. */
    public static final int DEFAULT_DAYS = 365;

    /**
     * Bounds the work: a request cannot ask the server to walk arbitrary history.
     *
     * <p>Counted as inclusive days, so a range may span at most this many days
     * end to end.
     */
    public static final int MAX_DAYS = 366;

    public DateRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("A range needs both ends");
        }
    }

    /**
     * Resolves a requested range, applying defaults and refusing an impossible one.
     *
     * <p>An absent end is today; an absent start is {@link #DEFAULT_DAYS} inclusive
     * days before that end. Supplying only a start is therefore a window ending
     * today, which is what "since" means.
     *
     * @throws BadRequestException if the start is after the end, or the span
     *     exceeds {@link #MAX_DAYS} inclusive days
     */
    public static DateRange resolve(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;

        if (start.isAfter(end)) {
            throw new BadRequestException("The start of the range must not be after its end");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new BadRequestException("The range must span at most " + MAX_DAYS + " days");
        }
        return new DateRange(start, end);
    }

    /** How many days the range covers, counting both ends. */
    public int days() {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    public boolean contains(LocalDate day) {
        return day != null && !day.isBefore(from) && !day.isAfter(to);
    }

    /** Every day in the range, oldest first, including days with nothing on them. */
    public List<LocalDate> eachDay() {
        List<LocalDate> days = new ArrayList<>(days());
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.add(day);
        }
        return days;
    }
}
