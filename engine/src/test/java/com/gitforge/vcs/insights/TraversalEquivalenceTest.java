package com.gitforge.vcs.insights;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The optimised traversal must answer exactly what the obvious one answers.
 *
 * <p>{@link BranchDivergence} and {@link RefComposition} stopped walking the
 * whole history once per reference. That is only worth having if the answers are
 * identical, so this holds the previous definitions - plain set arithmetic over
 * full ancestor sets - beside the new implementation and requires them to agree
 * on every shape that can be built.
 *
 * <p>The reference implementations here are not a paraphrase of the new code.
 * They are the arithmetic the old versions performed: ahead is the size of the
 * branch ancestry minus the base ancestry, behind is the reverse, related is
 * whether the two intersect, and the tag figure is the union of tag ancestries
 * minus the union of everything else. Written that way on purpose, so that a
 * mistake in the optimisation cannot be mirrored by a matching mistake here.
 *
 * <p>Shapes matter more than size. A linear history hides almost every way this
 * can go wrong, so the fixtures below include merges, a branch that is a pure
 * ancestor, a branch that is a pure descendant, criss-crossed merges with two
 * distinct merge bases, histories with no common root at all, and tags pointing
 * both onto and away from the mainline.
 */
class TraversalEquivalenceTest {

    private static final Signature AUTHOR =
            Signature.of("equiv", "equiv@localhost", Instant.parse("2026-01-01T00:00:00Z"));

    @TempDir
    Path storage;

    private VcsRepository repository;

    private VcsRepository repository() {
        if (repository == null) {
            repository = new VcsRepositoryFactory(storage)
                    .initialise(RepositoryId.of("equivalence"), "main");
        }
        return repository;
    }

    private ObjectId commit(String branch, String file, String content, String message) {
        return repository().commits().commit(
                branch,
                List.of(new FileChange.Put(
                        file, content.getBytes(StandardCharsets.UTF_8), FileMode.REGULAR_FILE)),
                AUTHOR,
                message);
    }

    private ObjectId line(String branch, String file, int count, String tag) {
        ObjectId tip = null;
        for (int i = 0; i < count; i++) {
            tip = commit(branch, file, tag + " " + i + "\n", tag + " commit " + i);
        }
        return tip;
    }

    // ------------------------------------------------------------- reference

    /** Divergence as it was computed before: full ancestor sets, then arithmetic. */
    private List<Branchwise> referenceDivergence(ObjectId base) {
        CommitGraph graph = new CommitGraph(repository().objects());
        Set<ObjectId> baseAncestry =
                base == null ? Set.of() : new LinkedHashSet<>(graph.bfs(base));

        List<Branchwise> rows = new ArrayList<>();
        for (String name : repository().branches().listBranches()) {
            Optional<ObjectId> tip = repository().branches().getBranch(name);
            if (tip.isEmpty()) {
                continue;
            }
            Set<ObjectId> branchAncestry = new LinkedHashSet<>(graph.bfs(tip.get()));

            int ahead = 0;
            for (ObjectId id : branchAncestry) {
                if (!baseAncestry.contains(id)) {
                    ahead++;
                }
            }
            int behind = 0;
            for (ObjectId id : baseAncestry) {
                if (!branchAncestry.contains(id)) {
                    behind++;
                }
            }
            boolean related = false;
            for (ObjectId id : branchAncestry) {
                if (baseAncestry.contains(id)) {
                    related = true;
                    break;
                }
            }
            rows.add(new Branchwise(name, tip.get(), ahead, behind, related));
        }
        return rows;
    }

    /** The tag figure as it was computed before: union of ancestries, then a difference. */
    private Set<ObjectId> referenceOnlyTagsProtect() {
        CommitGraph graph = new CommitGraph(repository().objects());
        var refs = repository().refs();

        Set<ObjectId> withoutTags = new LinkedHashSet<>();
        for (String branch : refs.listBranches()) {
            refs.getBranch(branch).ifPresent(tip -> withoutTags.addAll(graph.bfs(tip)));
        }
        refs.resolveHead().ifPresent(tip -> withoutTags.addAll(graph.bfs(tip)));
        refs.listRemoteRefs().forEach(ref -> withoutTags.addAll(graph.bfs(ref.commit())));

        Set<ObjectId> fromTags = new LinkedHashSet<>();
        for (String tag : refs.listTags()) {
            refs.getTag(tag).ifPresent(target ->
                    peel(target).ifPresent(id -> fromTags.addAll(graph.bfs(id))));
        }
        fromTags.removeAll(withoutTags);
        return fromTags;
    }

    /** The same peeling rule, written out here so the reference owes nothing to the code it checks. */
    private Optional<ObjectId> peel(ObjectId target) {
        ObjectId current = target;
        for (int depth = 0; depth <= com.gitforge.vcs.ref.TagService.MAX_PEEL_DEPTH; depth++) {
            Optional<com.gitforge.vcs.object.VcsObject> object = repository().objects().read(current);
            if (object.isEmpty()) {
                return Optional.empty();
            }
            if (object.get() instanceof com.gitforge.vcs.object.Commit) {
                return Optional.of(current);
            }
            if (object.get() instanceof com.gitforge.vcs.object.Tag tag) {
                current = tag.target();
                continue;
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private record Branchwise(String name, ObjectId tip, int ahead, int behind, boolean related) {
    }

    private List<Branchwise> optimisedDivergence(ObjectId base) {
        BranchDivergence divergence = new BranchDivergence(
                repository().refs(),
                repository().branches(),
                new CommitGraph(repository().objects()));
        return divergence.against(base).stream()
                .map(row -> new Branchwise(row.name(), row.tip(), row.ahead(), row.behind(), row.related()))
                .toList();
    }

    private Set<ObjectId> optimisedOnlyTagsProtect() {
        return new RefComposition(
                repository().refs(),
                repository().objects(),
                new CommitGraph(repository().objects()))
                .commitsOnlyTagsProtect();
    }

    private void assertAgreesOn(ObjectId base) {
        assertThat(optimisedDivergence(base))
                .as("divergence against " + base)
                .containsExactlyElementsOf(referenceDivergence(base));
    }

    private void assertTagFigureAgrees() {
        Set<ObjectId> expected = referenceOnlyTagsProtect();
        Set<ObjectId> actual = optimisedOnlyTagsProtect();
        assertThat(actual).as("the same commits").containsExactlyInAnyOrderElementsOf(expected);
        assertThat(new ArrayList<>(actual))
                .as("and in the same order, because the result is an ordered set")
                .isEqualTo(new ArrayList<>(expected));
    }

    // ---------------------------------------------------------------- shapes

    @Nested
    @DisplayName("the two implementations agree")
    class Agreement {

        @Test
        void onALinearHistory() {
            line("main", "a.txt", 12, "main");
            repository().branches().createBranchFrom("later", "main");
            assertAgreesOn(repository().branches().headCommit().orElseThrow());
        }

        @Test
        void onAPureAncestorAndAPureDescendant() {
            line("main", "a.txt", 10, "main");
            ObjectId halfway = repository().reader().resolve("main~5").orElseThrow();
            repository().branches().createBranch("behindOnly", halfway);

            ObjectId tip = repository().branches().headCommit().orElseThrow();
            repository().branches().createBranch("aheadOnly", tip);
            commit("aheadOnly", "b.txt", "extra\n", "Ahead of main");

            assertAgreesOn(tip);
            assertAgreesOn(halfway);
        }

        @Test
        void onDivergedBranchesWithAMerge() {
            line("main", "a.txt", 6, "main");
            ObjectId forkPoint = repository().branches().headCommit().orElseThrow();

            repository().branches().createBranch("feature", forkPoint);
            line("feature", "f.txt", 4, "feature");
            line("main", "a.txt", 3, "more main");

            repository().merges().merge("main", "feature", AUTHOR, AUTHOR, "Merge feature");

            repository().branches().createBranch("stale", forkPoint);
            assertAgreesOn(repository().branches().getBranch("main").orElseThrow());
            assertAgreesOn(repository().branches().getBranch("stale").orElseThrow());
        }

        @Test
        void onCrissCrossedMergesWithTwoMergeBases() {
            line("main", "a.txt", 4, "main");
            ObjectId fork = repository().branches().headCommit().orElseThrow();

            repository().branches().createBranch("left", fork);
            repository().branches().createBranch("right", fork);
            line("left", "l.txt", 2, "left");
            line("right", "r.txt", 2, "right");

            // Each side takes the other, so neither merge base dominates.
            repository().merges().merge("left", "right", AUTHOR, AUTHOR, "left takes right");
            repository().merges().merge("right", "left", AUTHOR, AUTHOR, "right takes left");

            line("left", "l.txt", 1, "left again");
            line("right", "r.txt", 1, "right again");

            assertAgreesOn(repository().branches().getBranch("left").orElseThrow());
            assertAgreesOn(repository().branches().getBranch("right").orElseThrow());
            assertAgreesOn(repository().branches().getBranch("main").orElseThrow());
        }

        @Test
        void onHistoriesWithNoCommonRoot() {
            line("main", "a.txt", 5, "main");
            // A second root: a branch created at a commit that shares no ancestry.
            ObjectId orphanRoot = repository().commits().commit(
                    "orphan",
                    List.of(new FileChange.Put(
                            "o.txt", "orphan\n".getBytes(StandardCharsets.UTF_8), FileMode.REGULAR_FILE)),
                    AUTHOR,
                    "Unrelated root");
            line("orphan", "o.txt", 3, "orphan");

            assertAgreesOn(repository().branches().getBranch("main").orElseThrow());
            assertAgreesOn(orphanRoot);

            // And the unrelated pair really is unrelated, so the case is doing
            // what its name says rather than passing vacuously.
            Branchwise orphanRow = optimisedDivergence(
                    repository().branches().getBranch("main").orElseThrow()).stream()
                    .filter(row -> row.name().equals("orphan"))
                    .findFirst()
                    .orElseThrow();
            assertThat(orphanRow.related()).isFalse();
            assertThat(orphanRow.behind()).isEqualTo(5);
        }

        @Test
        void whenThereIsNoBaseAtAll() {
            line("main", "a.txt", 4, "main");
            assertAgreesOn(null);
        }

        @Test
        void onASingleCommitRepository() {
            commit("main", "a.txt", "only\n", "Only commit");
            assertAgreesOn(repository().branches().headCommit().orElseThrow());
        }

        @Test
        void onTheTagFigureAcrossMixedShapes() {
            line("main", "a.txt", 5, "main");
            ObjectId fork = repository().branches().headCommit().orElseThrow();

            repository().branches().createBranch("kept", fork);
            line("kept", "k.txt", 2, "kept");

            // One tag on the mainline, protecting nothing by itself.
            repository().tags().createLightweight("on-mainline", fork);

            // One tag on history no branch reaches, which is the whole point of
            // the figure.
            repository().branches().createBranch("temp", fork);
            ObjectId tagged = line("temp", "t.txt", 3, "tagged");
            repository().tags().createLightweight("only-tag", tagged);
            repository().branches().deleteBranch("temp");

            assertTagFigureAgrees();
            assertThat(optimisedOnlyTagsProtect())
                    .as("the tag really is protecting something")
                    .isNotEmpty();
        }

        @Test
        void onTheTagFigureWhenEveryTagIsAlreadyReachable() {
            line("main", "a.txt", 6, "main");
            repository().tags().createLightweight("v1", repository().reader().resolve("main~2").orElseThrow());
            repository().tags().createLightweight("v2", repository().branches().headCommit().orElseThrow());

            assertTagFigureAgrees();
            assertThat(optimisedOnlyTagsProtect()).isEmpty();
        }
    }

    // ------------------------------------------------- cache and mutation

    @Nested
    @DisplayName("memoised traversal stays correct")
    class Memoisation {

        @Test
        @DisplayName("repeated calls on one graph give the same answer")
        void repeatedCallsAgree() {
            line("main", "a.txt", 8, "main");
            ObjectId base = repository().branches().headCommit().orElseThrow();
            repository().branches().createBranch("other", repository().reader().resolve("main~3").orElseThrow());

            BranchDivergence divergence = new BranchDivergence(
                    repository().refs(),
                    repository().branches(),
                    new CommitGraph(repository().objects()));

            var first = divergence.against(base);
            var second = divergence.against(base);
            var third = divergence.against(base);

            assertThat(second).isEqualTo(first);
            assertThat(third).isEqualTo(first);
        }

        @Test
        @DisplayName("a graph built after a mutation sees the mutation")
        void mutationThenTraversal() {
            line("main", "a.txt", 4, "main");
            ObjectId before = repository().branches().headCommit().orElseThrow();
            assertAgreesOn(before);

            line("main", "a.txt", 3, "later");
            ObjectId after = repository().branches().headCommit().orElseThrow();

            assertAgreesOn(after);

            var rows = optimisedDivergence(after);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).ahead()).isZero();
            assertThat(rows.get(0).behind()).isZero();
        }

        @Test
        @DisplayName("a graph held across a mutation still answers correctly for what it is asked")
        void graphHeldAcrossAMutation() {
            line("main", "a.txt", 4, "main");
            ObjectId base = repository().branches().headCommit().orElseThrow();

            CommitGraph graph = new CommitGraph(repository().objects());
            List<ObjectId> beforeWalk = graph.bfs(base);

            // New commits appear. The old tip is unchanged - its id is the hash
            // of its content - so the walk from it must be unchanged too.
            line("main", "a.txt", 3, "after");

            assertThat(graph.bfs(base))
                    .as("an id resolves to the same history however much is added elsewhere")
                    .isEqualTo(beforeWalk);
            assertThat(graph.bfs(repository().branches().headCommit().orElseThrow()))
                    .as("and the same graph sees the new commits when asked about them")
                    .hasSize(7);
        }

        @Test
        @DisplayName("collection does not leave a cached edge pointing at nothing")
        void afterCollection() {
            line("main", "a.txt", 5, "main");
            ObjectId keep = repository().branches().headCommit().orElseThrow();

            repository().branches().createBranch("doomed", keep);
            ObjectId unreachable = line("doomed", "d.txt", 2, "doomed");
            CommitGraph graph = new CommitGraph(repository().objects());
            graph.bfs(unreachable);

            repository().branches().deleteBranch("doomed");
            repository().gc().collect();

            // The surviving history is still walkable and still right.
            assertThat(new CommitGraph(repository().objects()).bfs(keep)).hasSize(5);
            assertAgreesOn(keep);
        }

        @Test
        @DisplayName("concurrent readers share a graph safely")
        void concurrentReaders() throws Exception {
            line("main", "a.txt", 20, "main");
            ObjectId base = repository().branches().headCommit().orElseThrow();
            repository().branches().createBranch("half", repository().reader().resolve("main~10").orElseThrow());

            CommitGraph shared = new CommitGraph(repository().objects());
            List<ObjectId> expected = shared.bfs(base);

            List<Throwable> failures = new CopyOnWriteArrayList<>();
            int readers = 8;
            ExecutorService pool = Executors.newFixedThreadPool(readers);
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < readers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int round = 0; round < 25; round++) {
                            if (!shared.bfs(base).equals(expected)) {
                                failures.add(new AssertionError("a reader saw a different history"));
                            }
                        }
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
            assertThat(failures).isEmpty();
        }
    }
}
