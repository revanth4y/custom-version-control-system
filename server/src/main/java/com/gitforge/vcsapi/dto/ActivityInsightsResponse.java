package com.gitforge.vcsapi.dto;

import java.time.LocalDate;

/**
 * What happened in a window: the equivalent of a project pulse.
 *
 * <p>Pull requests and reviews are deliberately absent rather than zeroed. This
 * engine has neither, and a field reading zero would suggest none happened
 * rather than that the concept does not exist here.
 *
 * @param contributors distinct authors who committed inside the window
 * @param tagsCreated annotated tags whose tagger time falls inside the window;
 *     lightweight tags carry no time of their own and so cannot be counted here
 */
public record ActivityInsightsResponse(
        LocalDate from,
        LocalDate to,
        int commits,
        int merges,
        int contributors,
        int issuesOpened,
        int issuesClosed,
        int releasesPublished,
        int tagsCreated) {
}
