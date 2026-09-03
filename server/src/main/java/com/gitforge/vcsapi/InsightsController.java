package com.gitforge.vcsapi;

import com.gitforge.security.AuthenticatedUser;
import com.gitforge.user.User;
import com.gitforge.vcsapi.dto.ActivityInsightsResponse;
import com.gitforge.vcsapi.dto.BranchInsightsResponse;
import com.gitforge.vcsapi.dto.ContributorInsightsResponse;
import com.gitforge.vcsapi.dto.DagInsightsResponse;
import com.gitforge.vcsapi.dto.HealthInsightsResponse;
import com.gitforge.vcsapi.dto.InsightsSeriesResponse;
import com.gitforge.vcsapi.dto.IssueInsightsResponse;
import com.gitforge.vcsapi.dto.RefInsightsResponse;
import com.gitforge.vcsapi.dto.ReleaseInsightsResponse;
import com.gitforge.vcsapi.dto.StorageInsightsResponse;
import com.gitforge.vcsapi.dto.TagInsightsResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The Insights sub-surface.
 *
 * <p>{@code GET /insights} itself is unchanged and stays where it has always
 * been; everything here is additive, so no existing caller is affected.
 *
 * <p>All reads, so the existing security rules already say the right thing:
 * {@code GET /repositories/**} is anonymous with visibility enforced in the
 * service layer. {@code SecurityConfig} needs no change.
 *
 * <p>Dates are ISO {@code from}/{@code to} query parameters, matching the
 * contributions endpoint, and are resolved by the shared {@code DateRange} —
 * defaults, inclusive ends and the 366-day ceiling all come from one place.
 */
@RestController
@RequestMapping("/api/v1/repositories/{owner}/{name}/insights")
public class InsightsController {

    private final RepositoryInsightsApiService insights;

    public InsightsController(RepositoryInsightsApiService insights) {
        this.insights = insights;
    }

    @GetMapping("/activity")
    public ActivityInsightsResponse activity(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.activity(owner, name, viewerOf(principal), from, to);
    }

    /** The shape of the commit graph: merges, depth, roots, history span. */
    @GetMapping("/commits")
    public DagInsightsResponse commits(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.dag(owner, name, viewerOf(principal));
    }

    /** Commits over time, gap-filled, at day or week grain. */
    @GetMapping("/commits/series")
    public InsightsSeriesResponse commitSeries(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String bucket,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.commitSeries(owner, name, viewerOf(principal), from, to, bucket);
    }

    @GetMapping("/contributors")
    public ContributorInsightsResponse contributors(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.contributors(owner, name, viewerOf(principal), from, to);
    }

    @GetMapping("/branches")
    public BranchInsightsResponse branches(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.branches(owner, name, viewerOf(principal));
    }

    @GetMapping("/refs")
    public RefInsightsResponse refs(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.refs(owner, name, viewerOf(principal));
    }

    @GetMapping("/tags")
    public TagInsightsResponse tags(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.tags(owner, name, viewerOf(principal));
    }

    @GetMapping("/releases")
    public ReleaseInsightsResponse releases(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.releases(owner, name, viewerOf(principal));
    }

    @GetMapping("/issues")
    public IssueInsightsResponse issues(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.issues(owner, name, viewerOf(principal), from, to);
    }

    @GetMapping("/storage")
    public StorageInsightsResponse storage(
            @PathVariable String owner,
            @PathVariable String name,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.storage(owner, name, viewerOf(principal));
    }

    /**
     * Repository health.
     *
     * <p><strong>{@code scan} defaults to false and must stay that way.</strong>
     * A true reachability sweep takes the repository's exclusive lock for its
     * whole duration, so making it the default would mean every page load blocked
     * commits. Asking for it is a deliberate act.
     */
    @GetMapping("/health")
    public HealthInsightsResponse health(
            @PathVariable String owner,
            @PathVariable String name,
            @RequestParam(required = false, defaultValue = "false") boolean scan,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return insights.health(owner, name, viewerOf(principal), scan);
    }

    /** Null for anonymous callers, which the service layer reads as public access only. */
    private static User viewerOf(AuthenticatedUser principal) {
        return principal == null ? null : principal.user();
    }
}
