package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.repo.Repo;
import com.gitforge.repo.RepoRepository;
import com.gitforge.repo.RepoVisibility;
import com.gitforge.user.User;
import com.gitforge.user.UserService;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import com.gitforge.vcsapi.dto.ContributionsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Daily commit counts for one person.
 *
 * <p>Computed from real commit timestamps every time it is asked for. Nothing is
 * pre-aggregated or stored, so the figures cannot drift away from the history
 * they describe.
 *
 * <p>Attribution is by <strong>author email</strong>, not repository ownership:
 * commits other people made in your repository are their work, not yours, and
 * commits you made anywhere count as yours.
 *
 * <p>Visibility is applied before any counting, so a stranger looking at a
 * profile never sees activity from private repositories — the same rule that
 * hides those repositories everywhere else.
 */
@Service
public class ContributionApiService {

    /** A year of history, which is what a contribution calendar shows. */
    private static final int DEFAULT_DAYS = 365;

    /** Bounds the work: a request cannot ask the server to walk arbitrary history. */
    private static final int MAX_DAYS = 366;

    private final UserService userService;
    private final RepoRepository repoRepository;
    private final VcsRepositoryFactory factory;

    public ContributionApiService(
            UserService userService, RepoRepository repoRepository, VcsRepositoryFactory factory) {
        this.userService = userService;
        this.repoRepository = repoRepository;
        this.factory = factory;
    }

    @Transactional(readOnly = true)
    public ContributionsResponse contributions(String username, User viewer, LocalDate from, LocalDate to) {
        User subject = userService.requireByUsername(username);

        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
        validateRange(start, end);

        Map<LocalDate, Integer> counts = new TreeMap<>();
        for (Repo repo : visibleRepositoriesOf(subject, viewer)) {
            countCommits(repo, subject, start, end, counts);
        }

        List<ContributionsResponse.Day> days = new ArrayList<>();
        int total = 0;
        // Every day in the window is present, including empty ones, so the client
        // renders a calendar without having to fill gaps itself.
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            int count = counts.getOrDefault(day, 0);
            days.add(new ContributionsResponse.Day(day, count));
            total += count;
        }
        return new ContributionsResponse(start, end, total, days);
    }

    private void countCommits(
            Repo repo, User subject, LocalDate start, LocalDate end, Map<LocalDate, Integer> counts) {

        if (!factory.exists(VcsRepositoryProvider.storageIdOf(repo))) {
            // A repository whose storage was never created simply has no commits.
            return;
        }
        VcsRepository vcs = factory.open(VcsRepositoryProvider.storageIdOf(repo));

        for (ObjectId id : reachableCommits(vcs)) {
            Commit commit = vcs.objects().readCommit(id);

            if (!subject.getEmail().equalsIgnoreCase(commit.author().email())) {
                continue;
            }
            LocalDate day = commit.author().timestamp().atZone(ZoneOffset.UTC).toLocalDate();
            if (!day.isBefore(start) && !day.isAfter(end)) {
                counts.merge(day, 1, Integer::sum);
            }
        }
    }

    /**
     * Every commit reachable from any branch, counted once.
     *
     * <p>Work on a side branch is still work; a commit reachable from two
     * branches is still one commit.
     */
    private static Set<ObjectId> reachableCommits(VcsRepository vcs) {
        Set<ObjectId> reachable = new LinkedHashSet<>();
        var graph = new com.gitforge.vcs.graph.CommitGraph(vcs.objects());

        for (String branch : vcs.branches().listBranches()) {
            vcs.branches().getBranch(branch).ifPresent(tip -> reachable.addAll(graph.bfs(tip)));
        }
        return reachable;
    }

    /** The subject's repositories that {@code viewer} is allowed to see. */
    private List<Repo> visibleRepositoriesOf(User subject, User viewer) {
        return subject.equals(viewer)
                ? repoRepository.findByOwnerOrderByUpdatedAtDesc(subject)
                : repoRepository.findByOwnerAndVisibilityOrderByUpdatedAtDesc(subject, RepoVisibility.PUBLIC);
    }

    private static void validateRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new BadRequestException("The start of the range must not be after its end");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new BadRequestException("The range must span at most " + MAX_DAYS + " days");
        }
    }
}
