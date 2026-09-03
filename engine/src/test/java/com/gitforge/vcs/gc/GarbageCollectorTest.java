package com.gitforge.vcs.gc;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.ref.Head;
import com.gitforge.vcs.repository.RepositoryLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a sweep keeps, and what it is allowed to take.
 *
 * <p>Every case here builds its garbage rather than looking for some: a
 * repository with nothing to collect proves only that deleting nothing is safe.
 */
class GarbageCollectorTest {

    @TempDir
    Path tempDir;

    private Path repositoryRoot;
    private RepositoryFixture repository;
    private ObjectId initialCommit;
    private ObjectId secondCommit;

    @BeforeEach
    void setUp() {
        repositoryRoot = tempDir.resolve("repo");
        repository = new RepositoryFixture(repositoryRoot, tempDir.resolve("work"));

        initialCommit = repository.commit("Initial commit", null, files(
                "README.md", "# Demo\n",
                "src/App.java", "class App {}\n"));
        secondCommit = repository.commit("Second commit", initialCommit, files(
                "README.md", "# Demo\n",
                "src/App.java", "class App { int x; }\n"));

        repository.branches().createBranch("main", secondCommit);
        repository.refStore().setHead(Head.onBranch("main"));
    }

    private GarbageCollector collector() {
        return new GarbageCollector(
                repository.objectStore(),
                repository.refStore(),
                repository.workTreeState(),
                new RepositoryLock());
    }

    private GarbageCollector collector(int ceiling) {
        return new GarbageCollector(
                repository.objectStore(),
                repository.refStore(),
                repository.workTreeState(),
                new RepositoryLock(),
                ceiling);
    }

    private Set<ObjectId> stored() {
        return Set.copyOf(repository.objectStore().listIds());
    }

    /** A commit on a branch that is then deleted: real, reproducible garbage. */
    private ObjectId strandedHistory() {
        ObjectId tip = repository.commit("Only on this branch", secondCommit,
                "extra.txt", "content\n");
        repository.branches().createBranch("doomed", tip);
        repository.branches().deleteBranch("doomed");
        return tip;
    }

    @Nested
    @DisplayName("nothing reachable is ever taken")
    class Reachable {

        @Test
        void aRepositoryWithNoGarbageLosesNothing() {
            Set<ObjectId> before = stored();

            GcReport report = collector().collect();

            assertThat(stored()).isEqualTo(before);
            assertThat(report.collected()).isEmpty();
            assertThat(report.unreachable()).isEmpty();
            assertThat(report.reachableObjects()).isEqualTo(before.size());
        }

        @Test
        void everyBlobAndTreeUnderAReachableCommitSurvives() {
            Set<ObjectId> before = stored();
            strandedHistory();

            collector().collect();

            // Everything that existed before the garbage was made is still here.
            assertThat(stored()).containsAll(before);
            assertThat(repository.objectStore().readCommit(initialCommit).message())
                    .isEqualTo("Initial commit\n");
            assertThat(repository.objectStore().readCommit(secondCommit).message())
                    .isEqualTo("Second commit\n");
        }

        @Test
        void aParentReachableOnlyThroughItsChildSurvives() {
            // Nothing names the initial commit; it is reachable only because the
            // branch tip lists it as a parent.
            assertThat(repository.branches().listBranches()).containsExactly("main");

            collector().collect();

            assertThat(repository.objectStore().contains(initialCommit)).isTrue();
        }

        @Test
        void aCommitReachableFromTwoBranchesSurvivesTheLossOfOne() {
            repository.branches().createBranch("second", secondCommit);
            repository.branches().deleteBranch("second");

            collector().collect();

            assertThat(repository.objectStore().contains(secondCommit)).isTrue();
        }

        @Test
        void bothParentsOfAMergeSurvive() {
            ObjectId sideBranch = repository.commit("Side work", initialCommit,
                    "side.txt", "side\n");
            ObjectId merge = repository.objectStore().write(Commit.of(
                    repository.objectStore().readCommit(secondCommit).tree(),
                    List.of(secondCommit, sideBranch),
                    new Signature("Ada Lovelace", "ada@example.com",
                            Instant.ofEpochSecond(1_700_001_000L), ZoneOffset.UTC),
                    "Merge side"));
            repository.branches().updateBranch("main", merge);

            collector().collect();

            // The second parent is reachable only as a parent - no branch names it.
            assertThat(repository.objectStore().contains(sideBranch)).isTrue();
            assertThat(repository.objectStore().contains(secondCommit)).isTrue();
            assertThat(repository.objectStore().contains(merge)).isTrue();
        }

        @Test
        void aRootCommitIsNotMistakenForGarbage() {
            collector().collect();

            assertThat(repository.objectStore().contains(initialCommit)).isTrue();
            assertThat(repository.objectStore().readCommit(initialCommit).parents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the root set is larger than the branches")
    class Roots {

        @Test
        void aCommitHeldOnlyByADetachedHeadSurvives() {
            ObjectId stranded = repository.commit("Held by HEAD alone", secondCommit,
                    "extra.txt", "content\n");
            repository.refStore().setHead(Head.detachedAt(stranded));

            // No branch reaches it: a branches-only traversal would delete this.
            assertThat(repository.branches().listBranches()).containsExactly("main");

            GcReport report = collector().collect();

            assertThat(repository.objectStore().contains(stranded)).isTrue();
            assertThat(report.collected()).doesNotContain(stranded);
        }

        @Test
        void aTreeHeldOnlyByTheWorkingTreeSurvives() throws IOException {
            ObjectId stranded = repository.commit("Materialized then abandoned", secondCommit,
                    "extra.txt", "content\n");
            ObjectId strandedTree = repository.objectStore().readCommit(stranded).tree();

            // The working tree records a tree, not a commit, and does not derive it
            // from HEAD. Nothing else in the root set implies it.
            repository.workTreeState().record(strandedTree);

            GcReport report = collector().collect();

            assertThat(repository.objectStore().contains(strandedTree)).isTrue();
            assertThat(report.collected()).doesNotContain(strandedTree);

            // The commit above it is not protected by the working tree, only its
            // tree is - so the tree surviving is not an accident of the commit.
            assertThat(report.collected()).contains(stranded);
        }

        @Test
        void theRootCountReflectsEveryReferenceIncludingHead() {
            repository.branches().createBranch("feature", initialCommit);

            GcReport report = collector().report();

            // main, feature, and HEAD - which resolves to main's tip, and is still
            // counted, because a root set that hid overlaps could hide a gap.
            assertThat(report.roots()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("unreachable history is collectible, but only when asked")
    class Collecting {

        @Test
        void deletingABranchAloneReclaimsNothing() {
            Set<ObjectId> before = stored();
            ObjectId tip = strandedHistory();

            // The branch is gone and the objects are not.
            assertThat(repository.branches().listBranches()).containsExactly("main");
            assertThat(repository.objectStore().contains(tip)).isTrue();
            assertThat(stored()).hasSizeGreaterThan(before.size());
        }

        @Test
        void anExplicitSweepCollectsWhatTheBranchLeftBehind() {
            Set<ObjectId> before = stored();
            ObjectId tip = strandedHistory();

            GcReport report = collector().collect();

            assertThat(report.collected()).contains(tip);
            assertThat(repository.objectStore().contains(tip)).isFalse();
            assertThat(stored()).isEqualTo(before);
        }

        @Test
        void aReportFindsTheGarbageAndLeavesItAlone() {
            ObjectId tip = strandedHistory();
            Set<ObjectId> before = stored();

            GcReport report = collector().report();

            assertThat(report.unreachable()).extracting(UnreachableObject::id).contains(tip);
            assertThat(report.collected()).isEmpty();
            assertThat(report.reclaimedBytes()).isZero();
            assertThat(report.collectionPerformed()).isFalse();
            assertThat(stored()).isEqualTo(before);
        }

        @Test
        void aReportAndACollectionAgreeOnWhatIsGarbage() {
            strandedHistory();

            List<ObjectId> predicted = collector().report().unreachable().stream()
                    .map(UnreachableObject::id)
                    .toList();
            List<ObjectId> actual = collector().collect().collected();

            assertThat(actual).containsExactlyInAnyOrderElementsOf(predicted);
        }

        @Test
        void everyCollectedObjectIsTypedAndMeasured() {
            strandedHistory();

            GcReport report = collector().report();

            assertThat(report.unreachable()).isNotEmpty();
            assertThat(report.unreachable()).allSatisfy(object -> {
                assertThat(object.type()).isIn(
                        ObjectType.BLOB, ObjectType.TREE, ObjectType.COMMIT);
                assertThat(object.bytes()).isPositive();
            });
            assertThat(report.unreachableBytes())
                    .isEqualTo(report.unreachable().stream()
                            .mapToLong(UnreachableObject::bytes).sum());
        }

        @Test
        void anOrphanedBlobIsCollected() {
            // A blob written and never referenced: what a refused merge leaves.
            ObjectId orphan = repository.objectStore().write(
                    new Blob("never referenced\n".getBytes(StandardCharsets.UTF_8)));

            GcReport report = collector().collect();

            assertThat(report.collected()).contains(orphan);
            assertThat(repository.objectStore().contains(orphan)).isFalse();
        }
    }

    @Nested
    @DisplayName("a sweep can be run again")
    class Idempotent {

        @Test
        void asecondSweepFindsNothingAndChangesNothing() {
            strandedHistory();

            GcReport first = collector().collect();
            Set<ObjectId> afterFirst = stored();
            GcReport second = collector().collect();

            assertThat(first.collected()).isNotEmpty();
            assertThat(second.collected()).isEmpty();
            assertThat(second.unreachable()).isEmpty();
            assertThat(stored()).isEqualTo(afterFirst);
        }

        @Test
        void sweepingAnEmptyRepositoryIsASafeNoOp() {
            Path emptyRoot = tempDir.resolve("empty");
            RepositoryFixture empty = new RepositoryFixture(emptyRoot, tempDir.resolve("empty-work"));

            GcReport report = new GarbageCollector(
                    empty.objectStore(), empty.refStore(), empty.workTreeState(),
                    new RepositoryLock()).collect();

            assertThat(report.storedObjects()).isZero();
            assertThat(report.reachableObjects()).isZero();
            assertThat(report.roots()).isZero();
            assertThat(report.collected()).isEmpty();
        }
    }

    @Nested
    @DisplayName("damage stops the sweep rather than being swept")
    class Damage {

        @Test
        void aDamagedReachableObjectAbandonsTheWholeSweep() throws IOException {
            ObjectId tip = strandedHistory();
            corrupt(secondCommit);

            assertThatThrownBy(() -> collector().collect())
                    .isInstanceOf(IncompleteReachabilityException.class)
                    .hasMessageContaining(secondCommit.toHex());

            // The garbage is still here: nothing was deleted on a partial picture.
            assertThat(repository.objectStore().contains(tip)).isTrue();
        }

        @Test
        void aMissingReachableObjectAbandonsTheWholeSweep() throws IOException {
            ObjectId tip = strandedHistory();
            Files.delete(objectPath(initialCommit));

            assertThatThrownBy(() -> collector().collect())
                    .isInstanceOf(IncompleteReachabilityException.class)
                    .hasMessageContaining(initialCommit.toHex());

            assertThat(repository.objectStore().contains(tip)).isTrue();
        }

        @Test
        void aDamagedUnreachableObjectIsReportedAndKept() throws IOException {
            ObjectId tip = strandedHistory();
            corrupt(tip);

            GcReport report = collector().collect();

            assertThat(report.retained())
                    .extracting(GcReport.RetainedObject::id)
                    .contains(tip);
            assertThat(report.retained())
                    .extracting(GcReport.RetainedObject::reason)
                    .contains(GcReport.Reason.DAMAGED);
            assertThat(report.collected()).doesNotContain(tip);
            assertThat(Files.exists(objectPath(tip))).isTrue();
        }
    }

    @Nested
    @DisplayName("bounds and staging files")
    class Bounds {

        @Test
        void aStoreOverTheCeilingCollectsNothingAtAll() {
            ObjectId tip = strandedHistory();
            long total = repository.objectStore().count();

            GcReport report = collector((int) total - 1).collect();

            assertThat(report.truncated()).isTrue();
            assertThat(report.collectionPerformed()).isFalse();
            assertThat(report.collected()).isEmpty();
            assertThat(report.unreachable()).isEmpty();
            assertThat(repository.objectStore().contains(tip)).isTrue();
        }

        @Test
        void aStagingFileIsReportedAndNeverRemoved() throws IOException {
            strandedHistory();
            Path shard = repositoryRoot.resolve("objects").resolve("ab");
            Files.createDirectories(shard);
            Path stray = shard.resolve(".tmp-abandoned");
            Files.writeString(stray, "half a write");

            GcReport report = collector().collect();

            assertThat(report.temporaryFiles()).containsExactly("ab/.tmp-abandoned");
            assertThat(Files.exists(stray)).isTrue();

            // It is not an object either: it never appears as collectible.
            assertThat(report.unreachable()).extracting(UnreachableObject::id)
                    .doesNotContainNull();
            assertThat(report.collected().stream().map(ObjectId::toHex).collect(Collectors.toSet()))
                    .allMatch(hex -> hex.length() == 40);
        }
    }

    private Path objectPath(ObjectId id) {
        String hex = id.toHex();
        return repositoryRoot.resolve("objects").resolve(hex.substring(0, 2)).resolve(hex.substring(2));
    }

    /** Replaces an object's bytes with something that will not decompress. */
    private void corrupt(ObjectId id) throws IOException {
        Files.write(objectPath(id), "not a compressed object".getBytes(StandardCharsets.UTF_8));
    }
}
