package com.gitforge.vcs.repository;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.tree.TreeWalker;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Aggregate facts about a repository, computed from the object store.
 *
 * <p>Nothing here is cached or stored: every figure is derived from the objects
 * and refs themselves, so a statistic can never disagree with the history it
 * describes. That is the same reason the commit graph is not persisted — a
 * derived copy is a copy that can go stale.
 *
 * <p>Commits are counted across <em>every</em> branch, deduplicated by object id,
 * so work on a side branch is included and a commit reachable from two branches
 * is still counted once.
 *
 * <p>Deliberately outside Spring, so the aggregation can be tested without an
 * application context.
 */
public final class RepositoryStatistics {

    private final ObjectStore objects;
    private final BranchService branches;
    private final CommitGraph graph;
    private final TreeWalker walker;

    RepositoryStatistics(ObjectStore objects, BranchService branches, CommitGraph graph) {
        this.objects = objects;
        this.branches = branches;
        this.graph = graph;
        this.walker = new TreeWalker(objects);
    }

    /**
     * @param commits distinct commits reachable from any branch
     * @param files files in the tree HEAD resolves to
     * @param storedObjects blobs, trees and commits held by this repository
     * @param contributors authors, most commits first
     * @param activity commit counts per UTC day, oldest first
     */
    public record Stats(
            int commits,
            int branches,
            int files,
            long storedObjects,
            List<Contributor> contributors,
            List<DailyCount> activity) {
    }

    public record Contributor(String name, String email, int commits) {
    }

    public record DailyCount(LocalDate date, int count) {
    }

    public Stats compute() {
        Set<ObjectId> reachable = reachableCommits();

        Map<String, Contributor> byEmail = new LinkedHashMap<>();
        Map<LocalDate, Integer> byDate = new TreeMap<>();

        for (ObjectId id : reachable) {
            Commit commit = objects.readCommit(id);

            // Identity is the email: a person may commit under several display
            // names, but the address is what identifies them.
            byEmail.merge(
                    commit.author().email(),
                    new Contributor(commit.author().name(), commit.author().email(), 1),
                    (existing, added) -> new Contributor(
                            existing.name(), existing.email(), existing.commits() + 1));

            LocalDate day = commit.author().timestamp().atZone(ZoneOffset.UTC).toLocalDate();
            byDate.merge(day, 1, Integer::sum);
        }

        List<Contributor> contributors = new ArrayList<>(byEmail.values());
        contributors.sort(Comparator
                .comparingInt(Contributor::commits).reversed()
                .thenComparing(Contributor::email));

        List<DailyCount> activity = byDate.entrySet().stream()
                .map(entry -> new DailyCount(entry.getKey(), entry.getValue()))
                .toList();

        return new Stats(
                reachable.size(),
                branches.listBranches().size(),
                countFiles(),
                objects.count(),
                contributors,
                activity);
    }

    /** Every commit reachable from any branch, counted once. */
    private Set<ObjectId> reachableCommits() {
        Set<ObjectId> reachable = new LinkedHashSet<>();
        for (String branch : branches.listBranches()) {
            branches.getBranch(branch).ifPresent(tip -> reachable.addAll(graph.bfs(tip)));
        }
        return reachable;
    }

    private int countFiles() {
        return branches.headCommit()
                .map(commit -> walker.flatten(objects.readCommit(commit).tree()).size())
                .orElse(0);
    }
}
