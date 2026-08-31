package com.gitforge.vcs.repository;

import com.gitforge.vcs.object.Commit;

import java.util.List;

/**
 * One page of history, and enough to ask for the next.
 *
 * <p>{@code walked} is the number of commits the traversal <em>consumed</em>,
 * which is not the same as the number returned once a path filter is involved:
 * a page of ten matching commits may have looked at four hundred to find them.
 * Continuing the walk requires the former — resuming from the number of matches
 * would re-examine everything the filter rejected, and every page after the
 * first would repeat commits the previous page had already dismissed.
 *
 * <p>{@code moreHistory} answers a question the commit list cannot. A short page
 * means "the traversal stopped", and it stops for two unrelated reasons: the
 * history ended, or the search budget ran out with the filter still unsatisfied.
 * Only the first is the end of anything, and a caller told merely that the page
 * was short cannot tell which happened.
 *
 * @param commits     the commits on this page, in traversal order
 * @param walked      how many commits the traversal consumed to produce them
 * @param moreHistory whether any commit remains beyond what was walked
 */
public record HistorySlice(List<Commit> commits, int walked, boolean moreHistory) {

    public HistorySlice {
        commits = List.copyOf(commits);
        if (walked < 0) {
            throw new IllegalArgumentException("Walked count must not be negative");
        }
    }

    /** Nothing found, nothing walked, nothing left — an unresolvable or empty history. */
    public static HistorySlice empty() {
        return new HistorySlice(List.of(), 0, false);
    }
}
