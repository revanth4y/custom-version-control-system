package com.gitforge.vcsapi.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Who wrote the history in a window.
 *
 * <p>Identity is the email address, matching every other contributor figure
 * here: one person may commit under several display names, and the address is
 * what identifies them.
 */
public record ContributorInsightsResponse(
        LocalDate from, LocalDate to, int total, List<Contributor> contributors) {

    /**
     * @param email the identity; already visible on the existing insights
     *     endpoint, and deliberately not exposed anywhere it was not before
     */
    public record Contributor(
            String name,
            String email,
            int commits,
            int merges,
            LocalDate firstCommit,
            LocalDate lastCommit) {
    }
}
