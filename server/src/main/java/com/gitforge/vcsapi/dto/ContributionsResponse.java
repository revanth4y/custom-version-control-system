package com.gitforge.vcsapi.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily commit counts over a window.
 *
 * @param days every day in the range, including those with no activity, so a
 *     calendar can be rendered without filling gaps client-side
 */
public record ContributionsResponse(LocalDate from, LocalDate to, int total, List<Day> days) {

    public record Day(LocalDate date, int count) {
    }
}
