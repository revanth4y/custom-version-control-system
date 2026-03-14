package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.DiffResultResponse;
import com.gitforge.vcsapi.dto.InsightsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Line-level diffs and repository statistics.
 *
 * <p>The structural comparison at {@code /compare} is unchanged and still
 * available; these add the line detail on top of it.
 */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}")
public class DiffController {

    private final DiffApiService diffs;
    private final InsightsApiService insights;

    public DiffController(DiffApiService diffs, InsightsApiService insights) {
        this.diffs = diffs;
        this.insights = insights;
    }

    @GetMapping("/diff")
    public DiffResultResponse diff(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam String base,
            @RequestParam String head,
            @RequestParam(required = false) String path,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return diffs.compare(owner, name, viewerOf(principal), base, head, path);
    }

    @GetMapping("/commits/{sha}/diff")
    public DiffResultResponse commitDiff(
            @PathVariable String owner,
            @PathVariable String name,
            @PathVariable String sha,
            @RequestParam(required = false) String path,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return diffs.commitDiff(owner, name, viewerOf(principal), sha, path);
    }

    @GetMapping("/insights")
    public InsightsResponse insights(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.insights(owner, name, viewerOf(principal));
    }

    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
