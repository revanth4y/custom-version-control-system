package com.gitforge.vcsapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of commit history.
 *
 * <p>Returned only when a caller asks to paginate. The unpaginated endpoint
 * still answers with a bare array, because it is public, anonymous, and has
 * consumers this repository cannot see — wrapping it for everyone would break
 * them all at once to spare one shape.
 *
 * <p><strong>{@code hasMore} is stated rather than implied.</strong> A client
 * could infer it from the presence of {@code nextCursor}, and the existing
 * {@code PageResponse} takes the same view with its {@code last} field: a
 * contract fact the client depends on should be in the payload, not derived from
 * the absence of something else. The interface asking "is there more history"
 * must not have to answer it by inspection.
 *
 * <p>{@code nextCursor} is omitted, not null, when the history ends — matching
 * how {@code ApiError} already omits what does not apply.
 *
 * <p>Deliberately not built on {@code PageResponse}: that record is an offset
 * model needing {@code totalElements} and {@code totalPages}, and counting the
 * commits in a repository means walking every one of them — the exact cost
 * paging exists to avoid. The fields it does carry, {@code page} and
 * {@code size}, mean nothing to a cursor walk.
 *
 * @param commits    the commits on this page, newest first
 * @param hasMore    whether any history remains beyond this page
 * @param nextCursor opaque; send it back to continue. Absent when {@code hasMore} is false
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommitPageResponse(
        List<CommitSummaryResponse> commits,
        boolean hasMore,
        String nextCursor) {

    public CommitPageResponse {
        commits = List.copyOf(commits);
    }

    /** A page that ends the walk. */
    public static CommitPageResponse last(List<CommitSummaryResponse> commits) {
        return new CommitPageResponse(commits, false, null);
    }

    /** A page with more behind it. */
    public static CommitPageResponse more(List<CommitSummaryResponse> commits, String nextCursor) {
        return new CommitPageResponse(commits, true, nextCursor);
    }
}
