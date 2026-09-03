package com.gitforge.insights;

import com.gitforge.common.error.BadRequestException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * The grain a series is counted at.
 *
 * <p>A bucket is named by the day it starts on, so a weekly series is a list of
 * Mondays rather than a list of week numbers. Week numbering is a surprisingly
 * regional thing — ISO weeks, US weeks and fiscal weeks all disagree — and a
 * date is unambiguous everywhere.
 *
 * <p><strong>Weeks start on Monday</strong>, following ISO-8601. That is the
 * convention the rest of this codebase's dates already imply by using
 * {@code java.time} defaults for ISO, and picking one and saying so is worth
 * more than picking the locale's, which would make two people's charts differ.
 */
public enum TimeBucket {

    DAY {
        @Override
        public LocalDate startOf(LocalDate day) {
            return day;
        }

        @Override
        public LocalDate next(LocalDate bucketStart) {
            return bucketStart.plusDays(1);
        }
    },

    WEEK {
        @Override
        public LocalDate startOf(LocalDate day) {
            return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }

        @Override
        public LocalDate next(LocalDate bucketStart) {
            return bucketStart.plusWeeks(1);
        }
    };

    /** The day the bucket containing {@code day} begins on. */
    public abstract LocalDate startOf(LocalDate day);

    /** The start of the bucket after this one. */
    public abstract LocalDate next(LocalDate bucketStart);

    /**
     * Parses a bucket name from a request.
     *
     * <p>Absent means {@link #DAY}, which is the finest grain and so the least
     * surprising default: a caller who did not choose gets the raw shape rather
     * than one this class picked for them.
     *
     * @throws BadRequestException if the name is not a bucket
     */
    public static TimeBucket parse(String name) {
        if (name == null || name.isBlank()) {
            return DAY;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown bucket: " + name + ". Use day or week.");
        }
    }
}
