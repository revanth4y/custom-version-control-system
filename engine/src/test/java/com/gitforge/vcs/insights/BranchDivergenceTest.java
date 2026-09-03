package com.gitforge.vcs.insights;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.ref.Head;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ahead and behind, for every relationship two branches can be in.
 *
 * <p>Ahead is what this branch has that the base does not; behind is what the
 * base has that this branch does not. Each relationship gets its own test
 * because the numbers are symmetrical and an implementation that swapped them
 * would still look plausible in any single case.
 */
class BranchDivergenceTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private BranchDivergence divergence;

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        divergence = new BranchDivergence(
                repository.refStore(),
                repository.branches(),
                new CommitGraph(repository.objectStore()));
    }

    private BranchDivergence.Branch of(String name, List<BranchDivergence.Branch> branches) {
        return branches.stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("relationships")
    class Relationships {

        @Test
        void anIdenticalBranchIsNeitherAheadNorBehind() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            repository.branches().createBranch("main", tip);
            repository.branches().createBranch("copy", tip);
            repository.refStore().setHead(Head.onBranch("main"));

            BranchDivergence.Branch copy = of("copy", divergence.againstHead());

            assertThat(copy.ahead()).isZero();
            assertThat(copy.behind()).isZero();
            assertThat(copy.identical()).isTrue();
            assertThat(copy.related()).isTrue();
        }

        @Test
        void anAncestorBranchIsBehindOnly() {
            ObjectId first = repository.commit("One", null, files("a.txt", "1\n"));
            ObjectId second = repository.commit("Two", first, files("a.txt", "2\n"));

            repository.branches().createBranch("main", second);
            repository.branches().createBranch("old", first);
            repository.refStore().setHead(Head.onBranch("main"));

            BranchDivergence.Branch old = of("old", divergence.againstHead());

            assertThat(old.ahead()).isZero();
            assertThat(old.behind()).isEqualTo(1);
            assertThat(old.ancestor()).isTrue();
            assertThat(old.diverged()).isFalse();
        }

        @Test
        void aDescendantBranchIsAheadOnly() {
            ObjectId first = repository.commit("One", null, files("a.txt", "1\n"));
            ObjectId second = repository.commit("Two", first, files("a.txt", "2\n"));
            ObjectId third = repository.commit("Three", second, files("a.txt", "3\n"));

            repository.branches().createBranch("main", first);
            repository.branches().createBranch("ahead", third);
            repository.refStore().setHead(Head.onBranch("main"));

            BranchDivergence.Branch branch = of("ahead", divergence.againstHead());

            assertThat(branch.ahead()).isEqualTo(2);
            assertThat(branch.behind()).isZero();
            assertThat(branch.descendant()).isTrue();
        }

        @Test
        void aDivergedBranchIsBothAheadAndBehind() {
            ObjectId base = repository.commit("Base", null, files("a.txt", "1\n"));
            ObjectId mine = repository.commit("Mine", base, files("m.txt", "m\n"));
            ObjectId theirs = repository.commit("Theirs", base, files("t.txt", "t\n"));
            ObjectId theirsMore = repository.commit("Theirs again", theirs, files("t.txt", "tt\n"));

            repository.branches().createBranch("main", theirsMore);
            repository.branches().createBranch("feature", mine);
            repository.refStore().setHead(Head.onBranch("main"));

            BranchDivergence.Branch feature = of("feature", divergence.againstHead());

            assertThat(feature.ahead()).isEqualTo(1);
            assertThat(feature.behind()).isEqualTo(2);
            assertThat(feature.diverged()).isTrue();
            assertThat(feature.related()).isTrue();
        }

        @Test
        void unrelatedHistoriesAreDescribedRatherThanRefused() {
            ObjectId mine = repository.commit("Mine", null, files("m.txt", "m\n"));
            ObjectId theirs = repository.commit("Theirs", null, files("t.txt", "t\n"));

            repository.branches().createBranch("main", mine);
            repository.branches().createBranch("orphan", theirs);
            repository.refStore().setHead(Head.onBranch("main"));

            BranchDivergence.Branch orphan = of("orphan", divergence.againstHead());

            // No shared commit, so each side holds everything the other lacks.
            assertThat(orphan.ahead()).isEqualTo(1);
            assertThat(orphan.behind()).isEqualTo(1);
            assertThat(orphan.related()).isFalse();
        }

        @Test
        void theCurrentBranchIsMarkedAndIsIdenticalToItself() {
            ObjectId tip = repository.commit("One", null, files("a.txt", "1\n"));
            repository.branches().createBranch("main", tip);
            repository.refStore().setHead(Head.onBranch("main"));

            BranchDivergence.Branch main = of("main", divergence.againstHead());

            assertThat(main.current()).isTrue();
            assertThat(main.identical()).isTrue();
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        void aRepositoryWithNoBranchesReportsNothing() {
            assertThat(divergence.againstHead()).isEmpty();
        }

        @Test
        void withNoBaseEveryBranchIsWhollyAhead() {
            ObjectId first = repository.commit("One", null, files("a.txt", "1\n"));
            ObjectId second = repository.commit("Two", first, files("a.txt", "2\n"));
            repository.branches().createBranch("solo", second);

            // Nothing to compare against: the branch's whole history is ahead of it.
            BranchDivergence.Branch solo = of("solo", divergence.against(null));

            assertThat(solo.ahead()).isEqualTo(2);
            assertThat(solo.behind()).isZero();
            assertThat(solo.related()).isFalse();
        }

        @Test
        void aheadAndBehindAreSymmetricBetweenTwoBranches() {
            ObjectId base = repository.commit("Base", null, files("a.txt", "1\n"));
            ObjectId mine = repository.commit("Mine", base, files("m.txt", "m\n"));
            ObjectId theirs = repository.commit("Theirs", base, files("t.txt", "t\n"));

            repository.branches().createBranch("mine", mine);
            repository.branches().createBranch("theirs", theirs);

            BranchDivergence.Branch fromTheirs = of("mine", divergence.against(theirs));
            BranchDivergence.Branch fromMine = of("theirs", divergence.against(mine));

            // What one is ahead by, the other is behind by.
            assertThat(fromTheirs.ahead()).isEqualTo(fromMine.behind());
            assertThat(fromTheirs.behind()).isEqualTo(fromMine.ahead());
        }
    }
}
