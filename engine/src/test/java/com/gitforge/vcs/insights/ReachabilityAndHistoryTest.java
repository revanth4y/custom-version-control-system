package com.gitforge.vcs.insights;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.gc.GarbageCollector;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.ref.TagService;
import com.gitforge.vcs.repository.RepositoryLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reachability health and history span.
 *
 * <p>Reachability is checked against collection's own answer rather than against
 * a number written here, because the point of this metric is that the two agree.
 */
class ReachabilityAndHistoryTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private TagService tags;
    private ReachabilityHealth health;
    private CommitInsights commits;

    private static final Signature TAGGER = new Signature(
            "Ada", "ada@example.test", Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        tags = new TagService(repository.refStore(), repository.objectStore(), new RepositoryLock());
        commits = new CommitInsights(repository.objectStore());

        GarbageCollector collector = new GarbageCollector(
                repository.objectStore(), repository.refStore(),
                repository.workTreeState(), new RepositoryLock());

        health = new ReachabilityHealth(
                repository.objectStore(), repository.refStore(),
                repository.workTreeState(), collector);
    }

    @Nested
    @DisplayName("cheap counts take no sweep")
    class Cheap {

        @Test
        void anEmptyRepositoryHasNoObjectsAndNoRoots() {
            ReachabilityHealth.Counts counts = health.cheapCounts();

            assertThat(counts.storedObjects()).isZero();
            assertThat(counts.roots()).isZero();
        }

        @Test
        void rootsAreCountedTheWayCollectionCountsThem() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            repository.branches().createBranch("main", tip);
            tags.createLightweight("v1", tip);
            repository.refStore().setHead(Head.onBranch("main"));

            // main, HEAD and the tag: duplicates kept, matching a sweep's own count.
            assertThat(health.cheapCounts().roots()).isEqualTo(3);
            assertThat(health.cheapCounts().roots()).isEqualTo(health.scan().roots());
        }
    }

    @Nested
    @DisplayName("an explicit scan")
    class Scan {

        @Test
        void everythingReachableReportsNothingUnreachable() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            repository.branches().createBranch("main", tip);

            ReachabilityHealth.Scan scan = health.scan();

            assertThat(scan.unreachableObjects()).isZero();
            assertThat(scan.unreachableBytes()).isZero();
            assertThat(scan.fullyReachable()).isTrue();
            assertThat(scan.reachableObjects()).isEqualTo(scan.storedObjects());
        }

        @Test
        void abandonedHistoryIsReportedAsUnreachable() {
            repository.branches().createBranch("main",
                    repository.commit("Main", null, files("m.txt", "m\n")));
            repository.commit("Stranded", null, files("s.txt", "s\n"));

            ReachabilityHealth.Scan scan = health.scan();

            assertThat(scan.unreachableObjects()).isPositive();
            assertThat(scan.unreachableBytes()).isPositive();
            assertThat(scan.fullyReachable()).isFalse();
        }

        @Test
        void aTagMakesOtherwiseAbandonedHistoryReachable() {
            repository.branches().createBranch("main",
                    repository.commit("Main", null, files("m.txt", "m\n")));
            ObjectId released = repository.commit("Released", null, files("r.txt", "r\n"));

            assertThat(health.scan().unreachableObjects()).isPositive();

            tags.createLightweight("v1", released);

            assertThat(health.scan().unreachableObjects()).isZero();
        }

        @Test
        void aRemoteTrackingRefMakesFetchedHistoryReachable() {
            repository.branches().createBranch("main",
                    repository.commit("Main", null, files("m.txt", "m\n")));
            ObjectId fetched = repository.commit("Fetched", null, files("f.txt", "f\n"));

            repository.refStore().setRemoteRef("origin", "main", fetched);

            assertThat(health.scan().unreachableObjects()).isZero();
        }

        @Test
        void aWorkTreeTreeKeepsItsObjectsReachable() {
            ObjectId onMain = repository.commit("Main", null, files("m.txt", "m\n"));
            repository.branches().createBranch("main", onMain);

            ObjectId strandedCommit = repository.commit("Stranded", null, files("s.txt", "s\n"));
            ObjectId strandedTree = repository.objectStore().readCommit(strandedCommit).tree();

            repository.workTreeState().record(strandedTree);

            ReachabilityHealth.Scan scan = health.scan();

            // The tree and its blob are protected; the commit above them is not.
            assertThat(scan.unreachableObjects()).isEqualTo(1);
        }

        @Test
        void aMixedStateReportsBothSides() {
            repository.branches().createBranch("main",
                    repository.commit("Main", null, files("m.txt", "m\n")));
            repository.commit("Stranded", null, files("s.txt", "s\n"));

            ReachabilityHealth.Scan scan = health.scan();

            assertThat(scan.reachableObjects()).isPositive();
            assertThat(scan.unreachableObjects()).isPositive();
            assertThat(scan.reachableObjects() + scan.unreachableObjects())
                    .isEqualTo(scan.storedObjects());
        }

        @Test
        void aScanCollectsNothing() {
            repository.branches().createBranch("main",
                    repository.commit("Main", null, files("m.txt", "m\n")));
            ObjectId stranded = repository.commit("Stranded", null, files("s.txt", "s\n"));

            health.scan();

            // report() is non-destructive; asking the question must not change the answer.
            assertThat(repository.objectStore().contains(stranded)).isTrue();
            assertThat(health.scan().unreachableObjects())
                    .isEqualTo(health.scan().unreachableObjects());
        }
    }

    @Nested
    @DisplayName("history span")
    class Span {

        private List<CommitInsights.Fact> factsOf(ObjectId... ids) {
            return commits.summarise(List.of(ids)).facts();
        }

        @Test
        void anEmptyRepositoryHasNoSpan() {
            assertThat(HistorySpan.of(List.of())).isEmpty();
            assertThat(HistorySpan.of(null)).isEmpty();
        }

        @Test
        void oneCommitSpansNoTime() {
            ObjectId only = repository.commit("One", null, files("a.txt", "1\n"));

            HistorySpan span = HistorySpan.of(factsOf(only)).orElseThrow();

            assertThat(span.earliestCommit()).isEqualTo(only);
            assertThat(span.latestCommit()).isEqualTo(only);
            assertThat(span.duration()).isEqualTo(Duration.ZERO);
            assertThat(span.instantaneous()).isTrue();
        }

        @Test
        void theSpanIsEarliestToLatestAuthoredCommit() {
            ObjectId first = repository.commit("One", null, files("a.txt", "1\n"));
            ObjectId second = repository.commit("Two", first, files("a.txt", "2\n"));
            ObjectId third = repository.commit("Three", second, files("a.txt", "3\n"));

            HistorySpan span = HistorySpan.of(factsOf(first, second, third)).orElseThrow();

            assertThat(span.earliestCommit()).isEqualTo(first);
            assertThat(span.latestCommit()).isEqualTo(third);
            assertThat(span.earliest()).isBefore(span.latest());
            assertThat(span.instantaneous()).isFalse();
        }

        @Test
        void theSpanIsCorrectWhenCommitsArriveOutOfOrder() {
            ObjectId first = repository.commit("One", null, files("a.txt", "1\n"));
            ObjectId second = repository.commit("Two", first, files("a.txt", "2\n"));
            ObjectId third = repository.commit("Three", second, files("a.txt", "3\n"));

            // Newest first: only a real comparison gets this right.
            HistorySpan span = HistorySpan.of(factsOf(third, first, second)).orElseThrow();

            assertThat(span.earliestCommit()).isEqualTo(first);
            assertThat(span.latestCommit()).isEqualTo(third);
        }

        @Test
        void anUnreadableCommitDoesNotEnterTheSpan() {
            ObjectId only = repository.commit("One", null, files("a.txt", "1\n"));
            ObjectId absent = ObjectId.fromHex("da39a3ee5e6b4b0d3255bfef95601890afd80709");

            HistorySpan span = HistorySpan.of(factsOf(only, absent)).orElseThrow();

            assertThat(span.earliestCommit()).isEqualTo(only);
            assertThat(span.latestCommit()).isEqualTo(only);
        }
    }
}
