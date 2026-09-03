package com.gitforge.vcs.insights;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
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
import java.time.Instant;
import java.time.ZoneOffset;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reference composition, tag analytics and storage composition.
 *
 * <p>The reconciliation assertions are the important ones: per-type counts must
 * sum to the total, per-type bytes to the total bytes, and a commit reachable
 * from two roots must be counted once. Those are the properties that fail
 * quietly if the arithmetic drifts.
 */
class RefAndStorageInsightsTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private TagService tags;
    private RefComposition refs;
    private StorageInsights storage;

    private static final Signature TAGGER = new Signature(
            "Ada", "ada@example.test", Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        tags = new TagService(repository.refStore(), repository.objectStore(), new RepositoryLock());
        refs = new RefComposition(
                repository.refStore(), repository.objectStore(), new CommitGraph(repository.objectStore()));
        storage = new StorageInsights(repository.objectStore());
    }

    @Nested
    @DisplayName("ref composition")
    class Composition {

        @Test
        void anEmptyRepositoryHasNoRefs() {
            RefComposition.Composition composition = refs.compute();

            assertThat(composition.branches()).isZero();
            assertThat(composition.tags()).isZero();
            assertThat(composition.remoteTrackingRefs()).isZero();
            assertThat(composition.total()).isZero();
            assertThat(composition.commitsOnlyTagsProtect()).isZero();
        }

        @Test
        void everyKindOfRefIsCounted() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            repository.branches().createBranch("main", tip);
            tags.createLightweight("v1", tip);
            repository.refStore().setRemoteRef("origin", "main", tip);
            repository.refStore().setHead(Head.onBranch("main"));

            RefComposition.Composition composition = refs.compute();

            assertThat(composition.branches()).isEqualTo(1);
            assertThat(composition.tags()).isEqualTo(1);
            assertThat(composition.remoteTrackingRefs()).isEqualTo(1);
            assertThat(composition.remotes()).isEqualTo(1);
            assertThat(composition.total()).isEqualTo(3);
            assertThat(composition.headAttached()).isTrue();
            assertThat(composition.headBranch()).isEqualTo("main");
        }

        @Test
        void aDeletedRefStopsBeingCountedImmediately() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            repository.branches().createBranch("main", tip);
            tags.createLightweight("v1", tip);

            assertThat(refs.compute().tags()).isEqualTo(1);

            tags.deleteTag("v1");

            assertThat(refs.compute().tags()).isZero();
        }

        @Test
        void aDetachedHeadIsReportedAsSuch() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            repository.refStore().setHead(Head.detachedAt(tip));

            RefComposition.Composition composition = refs.compute();

            assertThat(composition.headAttached()).isFalse();
            assertThat(composition.headBranch()).isNull();
        }
    }

    @Nested
    @DisplayName("commits only tags protect")
    class OnlyTags {

        @Test
        void aTagOnTheMainLineProtectsNothingExclusively() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            repository.branches().createBranch("main", tip);
            tags.createLightweight("v1", tip);

            // The branch reaches everything the tag does, so the exclusive set is
            // empty — an informative zero, not a gap.
            assertThat(refs.commitsOnlyTagsProtect()).isEmpty();
        }

        @Test
        void aTagOnAbandonedHistoryProtectsItAlone() {
            ObjectId onMain = repository.commit("Main", null, files("m.txt", "m\n"));
            repository.branches().createBranch("main", onMain);

            ObjectId released = repository.commit("Released", null, files("r.txt", "r\n"));
            tags.createLightweight("v1", released);

            assertThat(refs.commitsOnlyTagsProtect()).containsExactly(released);
            assertThat(refs.compute().commitsOnlyTagsProtect()).isEqualTo(1);
        }

        @Test
        void twoTagsOnOneCommitCountItOnce() {
            repository.branches().createBranch("main",
                    repository.commit("Main", null, files("m.txt", "m\n")));

            ObjectId released = repository.commit("Released", null, files("r.txt", "r\n"));
            tags.createLightweight("v1", released);
            tags.createLightweight("v1-also", released);

            assertThat(refs.commitsOnlyTagsProtect()).containsExactly(released);
        }

        @Test
        void anAnnotatedTagIsPeeledBeforeTheDifferenceIsTaken() {
            repository.branches().createBranch("main",
                    repository.commit("Main", null, files("m.txt", "m\n")));

            ObjectId released = repository.commit("Released", null, files("r.txt", "r\n"));
            tags.createAnnotated("v1", released, TAGGER, "Release\n");

            assertThat(refs.commitsOnlyTagsProtect()).containsExactly(released);
        }

        @Test
        void aRemoteTrackingRefRemovesTheCommitFromTheExclusiveSet() {
            repository.branches().createBranch("main",
                    repository.commit("Main", null, files("m.txt", "m\n")));

            ObjectId released = repository.commit("Released", null, files("r.txt", "r\n"));
            tags.createLightweight("v1", released);
            repository.refStore().setRemoteRef("origin", "release", released);

            // Something other than a tag now reaches it, so it is no longer
            // protected only by the tag.
            assertThat(refs.commitsOnlyTagsProtect()).isEmpty();
        }

        @Test
        void theWholeAncestryBehindATaggedTipCounts() {
            repository.branches().createBranch("main",
                    repository.commit("Main", null, files("m.txt", "m\n")));

            ObjectId first = repository.commit("First", null, files("r.txt", "1\n"));
            ObjectId second = repository.commit("Second", first, files("r.txt", "2\n"));
            tags.createLightweight("v1", second);

            assertThat(refs.commitsOnlyTagsProtect()).containsExactlyInAnyOrder(first, second);
        }
    }

    @Nested
    @DisplayName("tag analytics")
    class Tags {

        private TagInsights insights() {
            return new TagInsights(tags);
        }

        @Test
        void anEmptyRepositoryHasNoTags() {
            TagInsights.Summary summary = insights().summarise();

            assertThat(summary.total()).isZero();
            assertThat(summary.annotated()).isZero();
            assertThat(summary.lightweight()).isZero();
            assertThat(summary.medianInterval()).isEmpty();
        }

        @Test
        void lightweightAndAnnotatedAreCountedSeparatelyAndSumToTheTotal() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            tags.createLightweight("v1", tip);
            tags.createAnnotated("v2", tip, TAGGER, "Release\n");

            TagInsights.Summary summary = insights().summarise();

            assertThat(summary.total()).isEqualTo(2);
            assertThat(summary.annotated()).isEqualTo(1);
            assertThat(summary.lightweight()).isEqualTo(1);
            assertThat(summary.annotated() + summary.lightweight()).isEqualTo(summary.total());
        }

        @Test
        void aLightweightTagCarriesNoTaggedAtTime() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            tags.createLightweight("v1", tip);

            assertThat(insights().summarise().facts().get(0).taggedAt()).isEmpty();
        }

        @Test
        void anAnnotatedTagPeelsToItsCommit() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            tags.createAnnotated("v1", tip, TAGGER, "Release\n");

            TagInsights.TagFact fact = insights().summarise().facts().get(0);

            assertThat(fact.annotated()).isTrue();
            assertThat(fact.target()).isNotEqualTo(tip);
            assertThat(fact.commit()).contains(tip);
        }

        @Test
        void aTagChainPeelsAllTheWayDown() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            var inner = tags.createAnnotated("inner", tip, TAGGER, "Inner\n");
            tags.createAnnotated("outer", inner.id(), TAGGER, "Outer\n");

            TagInsights.TagFact outer = insights().summarise().facts().stream()
                    .filter(fact -> fact.name().equals("outer")).findFirst().orElseThrow();

            assertThat(outer.commit()).contains(tip);
        }

        @Test
        void cadenceNeedsAtLeastTwoAnnotatedTags() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            tags.createAnnotated("v1", tip, TAGGER, "Release\n");
            tags.createLightweight("v2", tip);

            // One annotated tag and one lightweight: still only one dated tag.
            assertThat(insights().summarise().medianInterval()).isEmpty();
        }

        @Test
        void cadenceIsMeasuredBetweenAnnotatedTagTimes() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            Signature early = new Signature("Ada", "ada@example.test",
                    Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
            Signature late = new Signature("Ada", "ada@example.test",
                    Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);

            tags.createAnnotated("v1", tip, early, "First\n");
            tags.createAnnotated("v2", tip, late, "Second\n");

            TagInsights.Summary summary = insights().summarise();

            assertThat(summary.medianInterval()).contains(java.time.Duration.ofDays(10));
            assertThat(summary.firstTagged()).contains(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(summary.lastTagged()).contains(Instant.parse("2026-01-11T00:00:00Z"));
        }
    }

    @Nested
    @DisplayName("storage composition")
    class Storage {

        @Test
        void anEmptyStoreReportsEveryTypeAtZero() {
            StorageInsights.Usage usage = storage.compute();

            assertThat(usage.storedObjects()).isZero();
            assertThat(usage.scannedObjects()).isZero();
            assertThat(usage.byType()).hasSize(ObjectType.values().length);
            assertThat(usage.byType()).allMatch(type -> type.count() == 0 && type.bytes() == 0);
            assertThat(usage.truncated()).isFalse();
        }

        @Test
        void everyObjectTypeInTheEnumIsRepresented() {
            repository.commit("One", null, files("a.txt", "1\n"));

            // Read from the enum, so a future fifth type appears here without this
            // test or the implementation being changed.
            assertThat(storage.compute().byType())
                    .extracting(StorageInsights.TypeUsage::type)
                    .containsExactlyInAnyOrder(ObjectType.values());
        }

        @Test
        void typeCountsSumToTheScannedTotal() {
            repository.commit("One", null, files("a.txt", "1\n", "b.txt", "2\n"));

            StorageInsights.Usage usage = storage.compute();

            int summed = usage.byType().stream().mapToInt(StorageInsights.TypeUsage::count).sum();

            assertThat(summed).isEqualTo(usage.scannedObjects());
        }

        @Test
        void typeBytesSumToTheScannedBytes() {
            repository.commit("One", null, files("a.txt", "1\n", "b.txt", "2\n"));

            StorageInsights.Usage usage = storage.compute();

            long summed = usage.byType().stream().mapToLong(StorageInsights.TypeUsage::bytes).sum();

            assertThat(summed).isEqualTo(usage.scannedBytes());
        }

        @Test
        void aCompleteScanMatchesTheStoresOwnCount() {
            repository.commit("One", null, files("a.txt", "1\n"));

            StorageInsights.Usage usage = storage.compute();

            assertThat(usage.complete()).isTrue();
            assertThat(usage.scannedObjects()).isEqualTo((int) usage.storedObjects());
        }

        @Test
        void aTagObjectIsCountedAsATag() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            tags.createAnnotated("v1", tip, TAGGER, "Release\n");

            StorageInsights.TypeUsage tagUsage = storage.compute().byType().stream()
                    .filter(usage -> usage.type() == ObjectType.TAG).findFirst().orElseThrow();

            assertThat(tagUsage.count()).isEqualTo(1);
            assertThat(tagUsage.bytes()).isPositive();
        }

        @Test
        void aTruncatedScanSaysSoRatherThanReportingAPartialTotal() {
            repository.commit("One", null, files("a.txt", "1\n", "b.txt", "2\n", "c.txt", "3\n"));

            StorageInsights bounded = new StorageInsights(repository.objectStore(), 2);
            StorageInsights.Usage usage = bounded.compute();

            assertThat(usage.truncated()).isTrue();
            assertThat(usage.complete()).isFalse();
            assertThat(usage.scannedObjects()).isLessThanOrEqualTo(2);
            // The store's own total is still reported honestly alongside it.
            assertThat(usage.storedObjects()).isGreaterThan(usage.scannedObjects());
        }
    }
}
