package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.CommitDetailResponse;
import com.gitforge.vcsapi.dto.CommitRequest;
import com.gitforge.vcsapi.dto.CommitSummaryResponse;
import com.gitforge.vcsapi.dto.CompareResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Creating commits, and reading history, detail and comparisons. */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class CommitController {

    private final CommitApiService commits;

    public CommitController(CommitApiService commits) {
        this.commits = commits;
    }

    @PostMapping("/commits")
    public ResponseEntity<CommitSummaryResponse> create(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CommitRequest request) {

        CommitSummaryResponse created = commits.commit(owner, name, principal.user(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * History reachable from a revision, optionally for one path.
     *
     * <p>{@code path} was refused outright until this version, because the
     * parameter had been silently dropped and the endpoint answered with the
     * whole branch's history whatever file you named — confidently wrong in a
     * way no caller could detect. It now does what it says: the commits that
     * touched that file or directory, newest first.
     *
     * <p>Callers that do not send it are unaffected.
     *
     * <p><strong>Two shapes, one rule.</strong> Asking to paginate — by sending
     * {@code paginate=true}, or by sending a {@code cursor}, which can only have
     * come from a paginated response — returns an object carrying the commits
     * alongside whether more exist. Everyone else gets the bare array this
     * endpoint has always returned.
     *
     * <p>The alternative was to wrap the array for everybody. This endpoint is
     * public and anonymous, so its callers are not all knowable from here, and
     * changing the shape underneath them to spare one branch in this method
     * would be charging them for our tidiness. The branch is here precisely so
     * it is not there.
     */
    @GetMapping("/commits")
    public Object history(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(required = false) String ref,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) Boolean paginate,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        User viewer = viewerOf(principal);
        if (Boolean.TRUE.equals(paginate) || (cursor != null && !cursor.isBlank())) {
            return commits.historyPage(owner, name, viewer, ref, limit, path, cursor);
        }
        return commits.history(owner, name, viewer, ref, limit, path);
    }

    @GetMapping("/commits/{sha}")
    public CommitDetailResponse detail(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable String sha,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return commits.detail(owner, name, viewerOf(principal), sha);
    }

    @GetMapping("/compare")
    public CompareResponse compare(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam String base,
            @RequestParam String head,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return commits.compare(owner, name, viewerOf(principal), base, head);
    }

    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
