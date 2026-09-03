package com.gitforge.vcs.insights;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a set of commits adds up to.
 *
 * <p>Two things carry the weight: a contributor is an email address rather than
 * a display name, and a merge is a commit with more than one parent. Both are
 * cheap to get subtly wrong in a way that produces numbers nobody questions.
 *
 * <p>Commits are written straight to the store here rather than through the
 * shared fixture, because these tests need to choose an author and a timestamp
 * per commit — and adding those knobs to a fixture every other suite depends on
 * would change something unrelated to what is being proven.
 */
class CommitInsightsTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private CommitInsights insights;
    private int sequence;

    private static final String ADA = "ada@example.test";
    private static final String BOB = "bob@example.test";

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        insights = new CommitInsights(repository.objectStore());
        sequence = 0;
    }

    private CommitInsights.Summary summarise(ObjectId... commits) {
        return insights.summarise(List.of(commits));
    }

    private ObjectId write(String name, String email, Instant when, List<ObjectId> parents) {
        ObjectId tree = repository.buildTree(files("f" + sequence + ".txt", "v" + sequence + "\n"));
        Signature who = new Signature(name, email, when, ZoneOffset.UTC);
        return repository.objectStore().write(
                new Commit(tree, parents, who, who, "commit " + sequence++ + "\n"));
    }

    private Instant later() {
        return Instant.ofEpochSecond(1_700_000_000L + sequence);
    }

    private Instant onDay(String isoDay) {
        return LocalDate.parse(isoDay).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(sequence);
    }

    /** An ordinary commit by Ada. */
    private ObjectId commit(ObjectId parent) {
        return write("Ada", ADA, later(), parent == null ? List.of() : List.of(parent));
    }

    private ObjectId commitBy(String name, String email, ObjectId parent) {
        return write(name, email, later(), parent == null ? List.of() : List.of(parent));
    }

    private ObjectId commitOn(String isoDay, ObjectId parent) {
        return write("Ada", ADA, onDay(isoDay), parent == null ? List.of() : List.of(parent));
    }

    private ObjectId merge(String email, ObjectId first, ObjectId second) {
        return write("Ada", email, later(), List.of(first, second));
    }

    @Nested
    @DisplayName("empty input")
    class Empty {

        @Test
        void noCommitsSummariseToZero() {
            CommitInsights.Summary summary = insights.summarise(List.of());

            assertThat(summary.commits()).isZero();
            assertThat(summary.merges()).isZero();
            assertThat(summary.nonMerges()).isZero();
            assertThat(summary.facts()).isEmpty();
            assertThat(summary.contributors()).isEmpty();
            assertThat(summary.countsByDay()).isEmpty();
            assertThat(summary.firstDay()).isEmpty();
            assertThat(summary.lastDay()).isEmpty();
        }
    }

    @Nested
    @DisplayName("contributor identity is the email")
    class Identity {

        @Test
        void twoDisplayNamesOnOneAddressAreOneContributor() {
            ObjectId a = commitBy("Ada", ADA, null);
            ObjectId b = commitBy("A. Lovelace", ADA, a);

            CommitInsights.Summary summary = summarise(a, b);

            assertThat(summary.contributors()).hasSize(1);
            assertThat(summary.contributors().get(0).email()).isEqualTo(ADA);
            assertThat(summary.contributors().get(0).commits()).isEqualTo(2);
        }

        @Test
        void theFirstNameSeenForAnAddressIsTheOneReported() {
            ObjectId a = commitBy("Ada", ADA, null);
            ObjectId b = commitBy("Someone Else", ADA, a);

            assertThat(summarise(a, b).contributors().get(0).name()).isEqualTo("Ada");
        }

        @Test
        void oneNameOnTwoAddressesIsTwoContributors() {
            ObjectId a = commitBy("Ada", "ada@work.test", null);
            ObjectId b = commitBy("Ada", "ada@home.test", a);

            assertThat(summarise(a, b).contributors()).hasSize(2);
        }

        @Test
        void contributorsAreRankedByCommitsThenEmail() {
            ObjectId a = commitBy("Ada", ADA, null);
            ObjectId b = commitBy("Ada", ADA, a);
            ObjectId c = commitBy("Bob", BOB, b);

            assertThat(summarise(a, b, c).contributors())
                    .extracting(CommitInsights.Contributor::email)
                    .containsExactly(ADA, BOB);
        }

        @Test
        void perContributorCommitsSumToTheTotal() {
            ObjectId a = commitBy("Ada", ADA, null);
            ObjectId b = commitBy("Bob", BOB, a);
            ObjectId c = commitBy("Ada", ADA, b);

            CommitInsights.Summary summary = summarise(a, b, c);

            assertThat(summary.contributors().stream()
                    .mapToInt(CommitInsights.Contributor::commits).sum())
                    .isEqualTo(summary.commits());
        }

        @Test
        void firstAndLastCommitDaysAreTracked() {
            ObjectId a = commitOn("2026-01-05", null);
            ObjectId b = commitOn("2026-03-09", a);

            CommitInsights.Contributor ada = summarise(a, b).contributors().get(0);

            assertThat(ada.firstCommit()).isEqualTo(LocalDate.parse("2026-01-05"));
            assertThat(ada.lastCommit()).isEqualTo(LocalDate.parse("2026-03-09"));
        }

        @Test
        void firstAndLastAreCorrectWhenCommitsArriveOutOfOrder() {
            ObjectId later = commitOn("2026-06-01", null);
            ObjectId earlier = commitOn("2026-01-01", null);

            // Summarised newest first, so this only passes if the comparison is real.
            CommitInsights.Contributor ada = summarise(later, earlier).contributors().get(0);

            assertThat(ada.firstCommit()).isEqualTo(LocalDate.parse("2026-01-01"));
            assertThat(ada.lastCommit()).isEqualTo(LocalDate.parse("2026-06-01"));
        }
    }

    @Nested
    @DisplayName("merge classification")
    class Merges {

        @Test
        void anOrdinaryCommitIsNotAMerge() {
            ObjectId a = commit(null);

            CommitInsights.Summary summary = summarise(a);

            assertThat(summary.merges()).isZero();
            assertThat(summary.nonMerges()).isEqualTo(1);
            assertThat(summary.facts().get(0).merge()).isFalse();
            assertThat(summary.facts().get(0).parents()).isZero();
        }

        @Test
        void aCommitWithOneParentIsNotAMerge() {
            ObjectId a = commit(null);
            ObjectId b = commit(a);

            assertThat(summarise(a, b).merges()).isZero();
            assertThat(summarise(a, b).facts().get(1).parents()).isEqualTo(1);
        }

        @Test
        void aCommitWithTwoParentsIsAMerge() {
            ObjectId base = commit(null);
            ObjectId left = commit(base);
            ObjectId merged = merge(ADA, left, base);

            CommitInsights.Summary summary = summarise(base, left, merged);

            assertThat(summary.merges()).isEqualTo(1);
            assertThat(summary.nonMerges()).isEqualTo(2);
            assertThat(summary.facts()).filteredOn(CommitInsights.Fact::merge)
                    .singleElement()
                    .satisfies(fact -> assertThat(fact.parents()).isEqualTo(2));
        }

        @Test
        void mergesAndNonMergesSumToTheTotal() {
            ObjectId base = commit(null);
            ObjectId left = commit(base);
            ObjectId merged = merge(ADA, left, base);

            CommitInsights.Summary summary = summarise(base, left, merged);

            assertThat(summary.merges() + summary.nonMerges()).isEqualTo(summary.commits());
        }

        @Test
        void aContributorsMergeCountIsTrackedSeparately() {
            ObjectId base = commit(null);
            ObjectId left = commit(base);
            ObjectId merged = merge(ADA, left, base);

            CommitInsights.Contributor ada = summarise(base, left, merged).contributors().get(0);

            assertThat(ada.commits()).isEqualTo(3);
            assertThat(ada.merges()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("days")
    class Days {

        @Test
        void commitsAreBucketedByUtcAuthorDay() {
            ObjectId a = commitOn("2026-01-05", null);
            ObjectId b = commitOn("2026-01-05", a);
            ObjectId c = commitOn("2026-01-06", b);

            CommitInsights.Summary summary = summarise(a, b, c);

            assertThat(summary.countsByDay())
                    .containsEntry(LocalDate.parse("2026-01-05"), 2)
                    .containsEntry(LocalDate.parse("2026-01-06"), 1);
        }

        @Test
        void perDayCountsSumToTheTotal() {
            ObjectId a = commitOn("2026-01-05", null);
            ObjectId b = commitOn("2026-02-11", a);

            CommitInsights.Summary summary = summarise(a, b);

            assertThat(summary.countsByDay().values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(summary.commits());
        }

        @Test
        void firstAndLastDayBoundTheHistory() {
            ObjectId a = commitOn("2026-01-05", null);
            ObjectId b = commitOn("2026-06-30", a);

            CommitInsights.Summary summary = summarise(a, b);

            assertThat(summary.firstDay()).contains(LocalDate.parse("2026-01-05"));
            assertThat(summary.lastDay()).contains(LocalDate.parse("2026-06-30"));
        }

        @Test
        void daysAreOrderedOldestFirst() {
            ObjectId later = commitOn("2026-06-01", null);
            ObjectId earlier = commitOn("2026-01-01", null);

            assertThat(summarise(later, earlier).countsByDay().keySet())
                    .containsExactly(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-01"));
        }
    }

    @Nested
    @DisplayName("one read, consistent answers")
    class Consistency {

        @Test
        void factsCountMatchesTheReportedCommitCount() {
            ObjectId a = commit(null);
            ObjectId b = commit(a);

            CommitInsights.Summary summary = summarise(a, b);

            assertThat(summary.facts()).hasSize(summary.commits());
        }

        @Test
        void anUnreadableCommitIsSkippedRatherThanFatal() {
            ObjectId a = commit(null);
            ObjectId absent = ObjectId.fromHex("da39a3ee5e6b4b0d3255bfef95601890afd80709");

            // Describing most of a repository beats refusing to describe any of it.
            CommitInsights.Summary summary = summarise(a, absent);

            assertThat(summary.commits()).isEqualTo(1);
            assertThat(summary.facts()).hasSize(1);
        }

        @Test
        void aStoreIsRequired() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CommitInsights(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("object store");
        }
    }
}
