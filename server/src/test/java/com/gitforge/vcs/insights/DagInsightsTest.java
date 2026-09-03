package com.gitforge.vcs.insights;

import com.gitforge.vcs.RepositoryFixture;
import com.gitforge.vcs.graph.CommitGraph;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.gitforge.vcs.RepositoryFixture.files;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shape of the commit graph, against DAGs whose answers are known exactly.
 *
 * <p>Every fixture here is built by hand so the expected figure can be stated
 * rather than computed by the code under test. A statistic checked against
 * itself proves nothing.
 */
class DagInsightsTest {

    @TempDir
    Path tempDir;

    private RepositoryFixture repository;
    private DagInsights dag;
    private int sequence;

    @BeforeEach
    void setUp() {
        repository = new RepositoryFixture(tempDir.resolve("repo"), tempDir.resolve("work"));
        dag = new DagInsights(repository.objectStore(), new CommitGraph(repository.objectStore()));
        sequence = 0;
    }

    private ObjectId commit(ObjectId... parents) {
        ObjectId tree = repository.buildTree(files("f" + sequence + ".txt", "v" + sequence + "\n"));
        Signature who = new Signature(
                "Ada", "ada@example.test", Instant.ofEpochSecond(1_700_000_000L + sequence), ZoneOffset.UTC);
        return repository.objectStore().write(
                new Commit(tree, List.of(parents), who, who, "commit " + sequence++ + "\n"));
    }

    @Nested
    @DisplayName("linear history")
    class Linear {

        @Test
        void anEmptySetHasNoShape() {
            DagInsights.Shape shape = dag.shapeOf(List.of());

            assertThat(shape.commits()).isZero();
            assertThat(shape.merges()).isZero();
            assertThat(shape.nonMerges()).isZero();
            assertThat(shape.roots()).isZero();
            assertThat(shape.maxDepth()).isZero();
            assertThat(shape.maxParents()).isZero();
            // A ratio nobody can compute is reported as zero rather than NaN.
            assertThat(shape.mergeRatio()).isZero();
        }

        @Test
        void aSingleCommitIsOneRootOfDepthOne() {
            ObjectId only = commit();

            DagInsights.Shape shape = dag.shapeOf(List.of(only));

            assertThat(shape.commits()).isEqualTo(1);
            assertThat(shape.roots()).isEqualTo(1);
            assertThat(shape.rootCommits()).containsExactly(only);
            assertThat(shape.maxDepth()).isEqualTo(1);
            assertThat(shape.maxParents()).isZero();
        }

        @Test
        void aChainOfThreeHasDepthThree() {
            ObjectId a = commit();
            ObjectId b = commit(a);
            ObjectId c = commit(b);

            DagInsights.Shape shape = dag.shapeOf(List.of(a, b, c));

            assertThat(shape.commits()).isEqualTo(3);
            assertThat(shape.merges()).isZero();
            assertThat(shape.nonMerges()).isEqualTo(3);
            assertThat(shape.maxDepth()).isEqualTo(3);
            assertThat(shape.roots()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("branching and merging")
    class Branching {

        @Test
        void aMergeIsCountedAndItsParentsReported() {
            ObjectId base = commit();
            ObjectId left = commit(base);
            ObjectId right = commit(base);
            ObjectId merged = commit(left, right);

            DagInsights.Shape shape = dag.shapeOf(List.of(base, left, right, merged));

            assertThat(shape.commits()).isEqualTo(4);
            assertThat(shape.merges()).isEqualTo(1);
            assertThat(shape.nonMerges()).isEqualTo(3);
            assertThat(shape.maxParents()).isEqualTo(2);
            assertThat(shape.mergeRatio()).isEqualTo(0.25);
        }

        @Test
        void depthFollowsTheLongestPathNotTheShortest() {
            //      base
            //      /   \
            //   left    right -> right2
            //      \        /
            //        merged
            ObjectId base = commit();
            ObjectId left = commit(base);
            ObjectId right = commit(base);
            ObjectId right2 = commit(right);
            ObjectId merged = commit(left, right2);

            DagInsights.Shape shape = dag.shapeOf(List.of(base, left, right, right2, merged));

            // base -> right -> right2 -> merged is four; base -> left -> merged is three.
            assertThat(shape.maxDepth()).isEqualTo(4);
        }

        @Test
        void anOctopusMergeReportsAllItsParents() {
            ObjectId base = commit();
            ObjectId a = commit(base);
            ObjectId b = commit(base);
            ObjectId c = commit(base);
            ObjectId octopus = commit(a, b, c);

            DagInsights.Shape shape = dag.shapeOf(List.of(base, a, b, c, octopus));

            assertThat(shape.maxParents()).isEqualTo(3);
            assertThat(shape.merges()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("multiple roots")
    class Roots {

        @Test
        void unrelatedHistoriesEachContributeARoot() {
            ObjectId firstRoot = commit();
            ObjectId firstChild = commit(firstRoot);
            ObjectId secondRoot = commit();

            DagInsights.Shape shape = dag.shapeOf(List.of(firstRoot, firstChild, secondRoot));

            assertThat(shape.roots()).isEqualTo(2);
            assertThat(shape.rootCommits()).containsExactlyInAnyOrder(firstRoot, secondRoot);
        }
    }

    @Nested
    @DisplayName("aggregate invariants")
    class Invariants {

        @Test
        void mergesAndNonMergesAlwaysSumToTheTotal() {
            ObjectId base = commit();
            ObjectId left = commit(base);
            ObjectId right = commit(base);
            ObjectId merged = commit(left, right);
            ObjectId after = commit(merged);

            DagInsights.Shape shape = dag.shapeOf(List.of(base, left, right, merged, after));

            assertThat(shape.merges() + shape.nonMerges()).isEqualTo(shape.commits());
        }

        @Test
        void theMergeRatioIsMergesOverCommits() {
            ObjectId base = commit();
            ObjectId left = commit(base);
            ObjectId merged = commit(left, base);

            DagInsights.Shape shape = dag.shapeOf(List.of(base, left, merged));

            assertThat(shape.mergeRatio()).isEqualTo(1.0 / 3.0);
        }

        @Test
        void duplicatesInTheInputAreCountedOnce() {
            ObjectId a = commit();
            ObjectId b = commit(a);

            assertThat(dag.shapeOf(List.of(a, b, a, b, a)).commits()).isEqualTo(2);
        }

        @Test
        void depthNeverExceedsTheCommitCount() {
            ObjectId a = commit();
            ObjectId b = commit(a);
            ObjectId c = commit(b);

            DagInsights.Shape shape = dag.shapeOf(List.of(a, b, c));

            assertThat(shape.maxDepth()).isLessThanOrEqualTo(shape.commits());
        }
    }

    @Nested
    @DisplayName("bounded to the counted set")
    class Bounded {

        @Test
        void aParentOutsideTheSetEndsTheChain() {
            ObjectId a = commit();
            ObjectId b = commit(a);
            ObjectId c = commit(b);

            // Only the tail is counted, so its parent is out of scope and the chain
            // stops rather than wandering into history the caller excluded.
            DagInsights.Shape shape = dag.shapeOf(Set.of(c));

            assertThat(shape.commits()).isEqualTo(1);
            assertThat(shape.maxDepth()).isEqualTo(1);
            // c has a parent, so it is not a root even though the parent is absent.
            assertThat(shape.roots()).isZero();
        }

        @Test
        void anUnreadableCommitIsSkippedRatherThanFatal() {
            ObjectId a = commit();
            ObjectId absent = ObjectId.fromHex("da39a3ee5e6b4b0d3255bfef95601890afd80709");

            DagInsights.Shape shape = dag.shapeOf(List.of(a, absent));

            assertThat(shape.commits()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void aStoreAndAGraphAreRequired() {
            assertThatThrownBy(() -> new DagInsights(null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("store and a graph");
        }
    }
}
