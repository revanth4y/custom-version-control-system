package com.gitforge.vcsapi;

import com.gitforge.insights.DateRange;
import com.gitforge.insights.IntegrityIndicator;
import com.gitforge.insights.ReleaseInsights;
import com.gitforge.insights.Series;
import com.gitforge.insights.TimeBucket;
import com.gitforge.issue.Issue;
import com.gitforge.issue.IssueRepository;
import com.gitforge.issue.IssueStatus;
import com.gitforge.release.Release;
import com.gitforge.release.ReleaseService;
import com.gitforge.repo.Repo;
import com.gitforge.repo.RepoService;
import com.gitforge.user.User;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.insights.BranchDivergence;
import com.gitforge.vcs.insights.CommitInsights;
import com.gitforge.vcs.insights.DagInsights;
import com.gitforge.vcs.insights.HistorySpan;
import com.gitforge.vcs.insights.ReachabilityHealth;
import com.gitforge.vcs.insights.RefComposition;
import com.gitforge.vcs.insights.StorageInsights;
import com.gitforge.vcs.insights.TagInsights;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcsapi.dto.ActivityInsightsResponse;
import com.gitforge.vcsapi.dto.BranchInsightsResponse;
import com.gitforge.vcsapi.dto.ContributorInsightsResponse;
import com.gitforge.vcsapi.dto.DagInsightsResponse;
import com.gitforge.vcsapi.dto.HealthInsightsResponse;
import com.gitforge.vcsapi.dto.InsightsSeriesResponse;
import com.gitforge.vcsapi.dto.IntegrityReport;
import com.gitforge.vcsapi.dto.IssueInsightsResponse;
import com.gitforge.vcsapi.dto.RefInsightsResponse;
import com.gitforge.vcsapi.dto.ReleaseInsightsResponse;
import com.gitforge.vcsapi.dto.StorageInsightsResponse;
import com.gitforge.vcsapi.dto.TagInsightsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Insights for the API.
 *
 * <p>Applies authorization and shapes results. Every figure is computed by the
 * services in {@code vcs.insights} and {@code insights}; nothing is aggregated
 * here, because a second implementation of a metric is how two endpoints start
 * disagreeing about one repository.
 *
 * <p>Reads go through {@code forRead}, so a private repository is invisible to a
 * stranger in exactly the way it already is elsewhere — reported absent rather
 * than forbidden.
 */
@Service
public class RepositoryInsightsApiService {

    private final VcsRepositoryProvider repositories;
    private final RepoService repoService;
    private final IssueRepository issues;
    private final ReleaseService releases;
    private final IntegrityApiService integrity;

    public RepositoryInsightsApiService(
            VcsRepositoryProvider repositories,
            RepoService repoService,
            IssueRepository issues,
            ReleaseService releases,
            IntegrityApiService integrity) {

        this.repositories = repositories;
        this.repoService = repoService;
        this.issues = issues;
        this.releases = releases;
        this.integrity = integrity;
    }

    // ------------------------------------------------------------------ commits

    public DagInsightsResponse dag(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        Set<ObjectId> reachable = repository.statistics().reachableCommits();
        DagInsights.Shape shape =
                new DagInsights(repository.objects(), graphOf(repository)).shapeOf(reachable);

        Optional<HistorySpan> span = HistorySpan.of(
                new CommitInsights(repository.objects()).summarise(reachable).facts());

        return new DagInsightsResponse(
                shape.commits(),
                shape.merges(),
                shape.nonMerges(),
                shape.mergeRatio(),
                shape.roots(),
                shape.rootCommits().stream().map(ObjectId::toHex).toList(),
                shape.maxDepth(),
                shape.maxParents(),
                span.map(HistorySpan::earliest).orElse(null),
                span.map(HistorySpan::latest).orElse(null),
                span.map(s -> s.duration().toSeconds()).orElse(null));
    }

    public InsightsSeriesResponse commitSeries(
            String owner, String name, User viewer, LocalDate from, LocalDate to, String bucket) {

        VcsRepository repository = repositories.forRead(owner, name, viewer);
        DateRange range = DateRange.resolve(from, to);
        TimeBucket grain = TimeBucket.parse(bucket);

        CommitInsights.Summary summary = summarise(repository);

        return InsightsSeriesResponse.of(
                range.from(),
                range.to(),
                grain.name().toLowerCase(Locale.ROOT),
                Series.of(range, grain, summary.countsByDay()));
    }

    public ContributorInsightsResponse contributors(
            String owner, String name, User viewer, LocalDate from, LocalDate to) {

        VcsRepository repository = repositories.forRead(owner, name, viewer);
        DateRange range = DateRange.resolve(from, to);

        // Restricted to the window before aggregating, so a contributor's counts
        // describe the range asked for rather than all of history.
        List<ObjectId> inRange = summarise(repository).facts().stream()
                .filter(fact -> range.contains(fact.day()))
                .map(CommitInsights.Fact::id)
                .toList();

        CommitInsights.Summary windowed =
                new CommitInsights(repository.objects()).summarise(inRange);

        return new ContributorInsightsResponse(
                range.from(),
                range.to(),
                windowed.contributors().size(),
                windowed.contributors().stream()
                        .map(c -> new ContributorInsightsResponse.Contributor(
                                c.name(), c.email(), c.commits(), c.merges(),
                                c.firstCommit(), c.lastCommit()))
                        .toList());
    }

    // ------------------------------------------------------------------ refs

    public BranchInsightsResponse branches(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        List<BranchDivergence.Branch> branches = new BranchDivergence(
                repository.refs(), repository.branches(), graphOf(repository)).againstHead();

        return new BranchInsightsResponse(
                repository.refs().resolveHead().map(ObjectId::toHex).orElse(null),
                branches.size(),
                branches.stream()
                        .map(branch -> new BranchInsightsResponse.Branch(
                                branch.name(),
                                branch.tip().toHex(),
                                branch.ahead(),
                                branch.behind(),
                                branch.current(),
                                branch.related()))
                        .toList());
    }

    public RefInsightsResponse refs(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        RefComposition.Composition composition = new RefComposition(
                repository.refs(), repository.objects(), graphOf(repository)).compute();

        return new RefInsightsResponse(
                composition.branches(),
                composition.tags(),
                composition.remoteTrackingRefs(),
                composition.remotes(),
                composition.total(),
                composition.headAttached(),
                composition.headBranch(),
                composition.commitsOnlyTagsProtect());
    }

    @Transactional(readOnly = true)
    public TagInsightsResponse tags(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);
        TagInsights.Summary summary = new TagInsights(repository.tags()).summarise();

        List<Release> visible = releases.list(owner, name, viewer);
        List<String> withoutRelease = ReleaseInsights.tagsWithoutReleases(
                summary.facts().stream().map(TagInsights.TagFact::name).toList(), visible);

        return new TagInsightsResponse(
                summary.total(),
                summary.annotated(),
                summary.lightweight(),
                summary.medianInterval().map(java.time.Duration::toSeconds).orElse(null),
                summary.firstTagged().orElse(null),
                summary.lastTagged().orElse(null),
                withoutRelease,
                summary.facts().stream()
                        .map(fact -> new TagInsightsResponse.Tag(
                                fact.name(),
                                fact.annotated(),
                                fact.target().toHex(),
                                fact.commit().map(ObjectId::toHex).orElse(null),
                                fact.taggedAt().orElse(null)))
                        .toList());
    }

    // ------------------------------------------------------------------ releases

    @Transactional(readOnly = true)
    public ReleaseInsightsResponse releases(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        // Visibility is applied by the release service before anything is counted,
        // so a draft never reaches the arithmetic for a viewer who may not see one.
        List<Release> visible = releases.list(owner, name, viewer);
        Set<String> existingTags = Set.copyOf(repository.tags().listTags());

        ReleaseInsights.Summary summary = ReleaseInsights.summarise(visible, existingTags);

        return new ReleaseInsightsResponse(
                summary.total(),
                summary.published(),
                summary.drafts(),
                summary.prereleases(),
                summary.withExistingTag(),
                summary.withMissingTag(),
                summary.medianInterval().map(java.time.Duration::toSeconds).orElse(null),
                summary.firstPublished().orElse(null),
                summary.lastPublished().orElse(null));
    }

    // ------------------------------------------------------------------ issues

    @Transactional(readOnly = true)
    public IssueInsightsResponse issues(
            String owner, String name, User viewer, LocalDate from, LocalDate to) {

        Repo repo = repoService.requireReadable(owner, name, viewer);
        DateRange range = DateRange.resolve(from, to);

        List<Issue> all = issues.findByRepoOrderByNumberDesc(repo);

        Map<LocalDate, Integer> openedByDay = new TreeMap<>();
        Map<LocalDate, Integer> closedByDay = new TreeMap<>();
        int open = 0;
        int closed = 0;
        int closedUndated = 0;
        int openedInRange = 0;
        int closedInRange = 0;

        for (Issue issue : all) {
            LocalDate openedOn = day(issue.getCreatedAt());
            if (openedOn != null) {
                openedByDay.merge(openedOn, 1, Integer::sum);
                if (range.contains(openedOn)) {
                    openedInRange++;
                }
            }

            if (issue.getStatus() == IssueStatus.CLOSED) {
                closed++;
                LocalDate closedOn = day(issue.getClosedAt());
                if (closedOn == null) {
                    // Closed before closure times were recorded. Counted as closed
                    // and kept out of the series, because it has no day to sit on
                    // and inventing one would be inventing history.
                    closedUndated++;
                } else {
                    closedByDay.merge(closedOn, 1, Integer::sum);
                    if (range.contains(closedOn)) {
                        closedInRange++;
                    }
                }
            } else {
                open++;
            }
        }

        return new IssueInsightsResponse(
                range.from(),
                range.to(),
                all.size(),
                open,
                closed,
                closedUndated,
                openedInRange,
                closedInRange,
                InsightsSeriesResponse.of(range.from(), range.to(), "day",
                        Series.of(range, TimeBucket.DAY, openedByDay)),
                InsightsSeriesResponse.of(range.from(), range.to(), "day",
                        Series.of(range, TimeBucket.DAY, closedByDay)));
    }

    // ------------------------------------------------------------------ activity

    @Transactional(readOnly = true)
    public ActivityInsightsResponse activity(
            String owner, String name, User viewer, LocalDate from, LocalDate to) {

        VcsRepository repository = repositories.forRead(owner, name, viewer);
        Repo repo = repoService.requireReadable(owner, name, viewer);
        DateRange range = DateRange.resolve(from, to);

        List<CommitInsights.Fact> inWindow = summarise(repository).facts().stream()
                .filter(fact -> range.contains(fact.day()))
                .toList();

        int merges = (int) inWindow.stream().filter(CommitInsights.Fact::merge).count();
        int contributors = (int) inWindow.stream()
                .map(CommitInsights.Fact::authorEmail).distinct().count();

        int issuesOpened = 0;
        int issuesClosed = 0;
        for (Issue issue : issues.findByRepoOrderByNumberDesc(repo)) {
            if (range.contains(day(issue.getCreatedAt()))) {
                issuesOpened++;
            }
            if (issue.getStatus() == IssueStatus.CLOSED && range.contains(day(issue.getClosedAt()))) {
                issuesClosed++;
            }
        }

        int releasesPublished = (int) releases.list(owner, name, viewer).stream()
                .filter(release -> !release.isDraft())
                .filter(release -> range.contains(day(release.getPublishedAt())))
                .count();

        int tagsCreated = (int) new TagInsights(repository.tags()).summarise().facts().stream()
                .map(TagInsights.TagFact::taggedAt)
                .flatMap(Optional::stream)
                .filter(when -> range.contains(day(when)))
                .count();

        return new ActivityInsightsResponse(
                range.from(),
                range.to(),
                inWindow.size(),
                merges,
                contributors,
                issuesOpened,
                issuesClosed,
                releasesPublished,
                tagsCreated);
    }

    // ------------------------------------------------------------------ storage

    public StorageInsightsResponse storage(String owner, String name, User viewer) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);
        StorageInsights.Usage usage = new StorageInsights(repository.objects()).compute();

        return new StorageInsightsResponse(
                usage.storedObjects(),
                usage.scannedObjects(),
                usage.scannedBytes(),
                usage.truncated(),
                usage.unreadable(),
                usage.byType().stream()
                        .map(type -> new StorageInsightsResponse.TypeUsage(
                                type.type().header(), type.count(), type.bytes()))
                        .toList());
    }

    // ------------------------------------------------------------------ health

    /**
     * Repository health.
     *
     * <p><strong>{@code scan} is opt-in and defaults to false, and it gates both
     * expensive operations rather than only the obvious one.</strong>
     *
     * <p>The reachability sweep walks every object and holds the repository's
     * exclusive lock while it does, so a page that ran one on every load would
     * block commits every time somebody looked at it. Integrity verification does
     * not take that lock, but it still reads and re-hashes every object, and
     * wiring an O(objects) scan into an ordinary page load is the same mistake in
     * a quieter form.
     *
     * <p>Without a scan, {@code integrity} is {@code NOT_VERIFIED} — which is not
     * a hedge but the literal truth: nothing was verified, so nothing about the
     * store's soundness has been established.
     */
    public HealthInsightsResponse health(String owner, String name, User viewer, boolean scan) {
        VcsRepository repository = repositories.forRead(owner, name, viewer);

        ReachabilityHealth reachability = new ReachabilityHealth(
                repository.objects(), repository.refs(), null, repository.gc());

        ReachabilityHealth.Counts counts = reachability.cheapCounts();

        if (!scan) {
            return new HealthInsightsResponse(
                    counts.storedObjects(), counts.roots(), false,
                    null, null, null, null, null, null, null,
                    IntegrityIndicator.NOT_VERIFIED.name(), 0, 0, false);
        }

        ReachabilityHealth.Scan swept = reachability.scan();
        IntegrityReport report = integrity.verify(owner, name, viewer);

        return new HealthInsightsResponse(
                counts.storedObjects(),
                counts.roots(),
                true,
                swept.reachableObjects(),
                swept.unreachableObjects(),
                swept.unreachableBytes(),
                swept.retained(),
                swept.truncated(),
                swept.fullyReachable(),
                swept.durationMs(),
                IntegrityIndicator.of(report).name(),
                report.verified(),
                report.damaged().size(),
                report.truncated());
    }

    // ------------------------------------------------------------------ helpers

    private static CommitGraph graphOf(VcsRepository repository) {
        return new CommitGraph(repository.objects());
    }

    /** One read of every reachable commit, from the canonical root set. */
    private static CommitInsights.Summary summarise(VcsRepository repository) {
        return new CommitInsights(repository.objects())
                .summarise(repository.statistics().reachableCommits());
    }

    private static LocalDate day(java.time.Instant instant) {
        return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
