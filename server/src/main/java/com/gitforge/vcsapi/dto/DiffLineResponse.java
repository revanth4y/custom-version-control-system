package com.gitforge.vcsapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gitforge.vcs.diff.DiffLine;

import java.util.List;

/**
 * One line of a hunk.
 *
 * @param type CONTEXT, ADDED or REMOVED
 * @param oldNumber null on added lines
 * @param newNumber null on removed lines
 * @param segments the runs of characters that changed within {@code content},
 *     omitted entirely rather than sent empty. Present only on a removed or
 *     added line that was paired with its counterpart and small enough to
 *     compare; absent on context lines, on unpaired lines, and whenever the
 *     comparison was declined. A client that ignores this field renders exactly
 *     as it did before the field existed.
 */
public record DiffLineResponse(
        String type,
        Integer oldNumber,
        Integer newNumber,
        String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<SegmentResponse> segments) {

    /**
     * Half-open character offsets into the line's own {@code content}, in the
     * same units the client's string uses.
     */
    public record SegmentResponse(int start, int end) {
    }

    public static DiffLineResponse from(DiffLine line) {
        return new DiffLineResponse(
                line.type().name(),
                line.oldNumber(),
                line.newNumber(),
                line.content(),
                line.segments().stream()
                        .map(segment -> new SegmentResponse(segment.start(), segment.end()))
                        .toList());
    }
}
