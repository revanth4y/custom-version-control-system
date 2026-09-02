package com.gitforge.vcs.repository;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tag;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.BranchService;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.ref.ReferenceRoots;
import com.gitforge.vcs.ref.TagService;
import com.gitforge.vcs.storage.ObjectStore;
import com.gitforge.vcs.tree.TreeWalker;
import com.gitforge.vcs.worktree.WorkTreeState;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p><strong>Commits are counted from the same roots garbage collection uses</strong>
 * — branches, HEAD, remote-tracking refs, tags and the materialized work tree, by
 * way of {@link ReferenceRoots}. For a while this walked branches alone, which
 * meant a commit reachable only through a tag was protected from deletion and
 * absent from every figure. A statistic that disagrees with what the repository
 * will keep is not a different opinion, it is a wrong one.
 *
 * <p>Results are deduplicated by object id, so work on a side branch is included
 * and a commit reachable from two roots is still counted once.
 *
 * <p>Deliberately outside Spring, so the aggregation can be tested without an
 * application context.
 */
public final class RepositoryStatistics {

    private final ObjectStore objects;
    private final BranchService branches;
    private final RefStore refs;
    private final WorkTreeState workTree;
    private final CommitGraph graph;
    private final TreeWalker walker;

    /**
     * @param workTree may be null, for a bare repository; a null working tree
     *     contributes no root, exactly as it does for collection
     */
    RepositoryStatistics(
            ObjectStore objects,
            BranchService branches,
            RefStore refs,
            WorkTreeState workTree,
            CommitGraph graph) {

        this.objects = objects;
        this.branches = branches;
        this.refs = refs;
        this.workTree = workTree;
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

    /**
     * Every commit reachable from any root, counted once.
     *
     * <p>Public because contribution counting needs exactly this set, and a second
     * implementation of it is what let statistics and collection drift apart in the
     * first place. One root set, one peeling rule, one answer.
     */
    public Set<ObjectId> reachableCommits() {
        Set<ObjectId> reachable = new LinkedHashSet<>();
        for (ObjectId root : ReferenceRoots.of(refs, workTree)) {
            commitAt(root).ifPresent(commit -> reachable.addAll(graph.bfs(commit)));
        }
        return reachable;
    }

    /**
     * The commit a root names, following tag objects, or empty if it names none.
     *
     * <p>Most roots are commits already. An annotated tag names a tag object and
     * has to be peeled — through a chain, since a tag may name a tag. The
     * materialized work-tree root names a <em>tree</em> and so contributes no
     * commit at all: it keeps objects alive without being part of anyone's
     * history, which is exactly why collection treats it as a root and counting
     * does not treat it as a commit.
     *
     * <p>A root that cannot be read is skipped rather than fatal. Statistics
     * describe a repository; refusing to describe any of it because one object is
     * damaged would be least useful precisely when it most needs looking at.
     */
    private Optional<ObjectId> commitAt(ObjectId root) {
        ObjectId current = root;
        for (int depth = 0; depth <= TagService.MAX_PEEL_DEPTH; depth++) {
            Optional<VcsObject> object;
            try {
                object = objects.read(current);
            } catch (CorruptObjectException ex) {
                return Optional.empty();
            }
            if (object.isEmpty()) {
                return Optional.empty();
            }
            if (object.get() instanceof Commit) {
                return Optional.of(current);
            }
            if (object.get() instanceof Tag tag) {
                current = tag.target();
                continue;
            }
            // A tree or a blob names no commit.
            return Optional.empty();
        }
        return Optional.empty();
    }

    private int countFiles() {
        return branches.headCommit()
                .map(commit -> walker.flatten(objects.readCommit(commit).tree()).size())
                .orElse(0);
    }
}
