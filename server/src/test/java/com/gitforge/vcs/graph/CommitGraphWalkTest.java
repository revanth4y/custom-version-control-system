package com.gitforge.vcs.graph;

import com.gitforge.vcs.CountingObjectStore;
import com.gitforge.vcs.GoldenVectors;
import com.gitforge.vcs.InMemoryObjectStore;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lazy traversal, and the claim that makes paging possible.
 *
 * <p>Two things are being proved here and they are different in kind. That
 * {@code walk} produces the same order as {@code bfs} is ordinary correctness.
 * That it does not <em>read</em> the whole history to produce the first page is
 * a claim about work not done, which a correct result alone cannot establish —
 * so the reads are counted.
 */
class CommitGraphWalkTest {

    private static final ObjectId TREE = ObjectId.fromHex(GoldenVectors.TREE_ROOT);

    private InMemoryObjectStore store;
    private CountingObjectStore counting;
    private CommitGraph graph;
    private int sequence;

    @BeforeEach
    void setUp() {
        store = new InMemoryObjectStore();
        counting = new CountingObjectStore(store);
        graph = new CommitGraph(counting);
        sequence = 0;
    }

    private ObjectId commit(String label, ObjectId... parents) {
        Signature author = new Signature(
                "Ada Lovelace",
                "ada@example.com",
                Instant.ofEpochSecond(GoldenVectors.SIGNATURE_EPOCH_SECONDS + sequence++),
                ZoneOffset.UTC);

        Commit created = Commit.of(TREE, List.of(parents), author, label);
        store.write(created);
        return created.id();
    }

    /** A chain of {@code length} commits, newest returned. */
    private ObjectId chain(int length) {
        ObjectId tip = commit("commit-0");
        for (int i = 1; i < length; i++) {
            tip = commit("commit-" + i, tip);
        }
        return tip;
    }

    @Nested
    @DisplayName("same order as bfs")
    class Ordering {

        @Test
        void linearHistoryMatches() {
            ObjectId tip = chain(5);

            assertThat(graph.walk(tip).toList()).isEqualTo(graph.bfs(tip));
        }

        @Test
        void mergeHistoryMatches() {
            //      a
            //     / \
            //    b   c
            //     \ /
            //      d
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", a);
            ObjectId d = commit("d", b, c);

            assertThat(graph.walk(d).toList()).isEqualTo(graph.bfs(d));
        }

        @Test
        void aSharedAncestorIsEmittedOnce() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", a);
            ObjectId d = commit("d", b, c);

            assertThat(graph.walk(d).toList()).containsExactly(d, b, c, a);
        }

        @Test
        void aParentReachableByBothAShortAndALongPathIsStillEmittedOnce() {
            /* The shape that defeats a resumption carrying only its frontier: b
               is shallow and emitted early, yet it is also the parent of a
               commit far down the other line. Nothing may emit it twice. */
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId far = b;
            for (int i = 0; i < 6; i++) {
                far = commit("long-" + i, far);
            }
            ObjectId tip = commit("tip", b, far);

            List<ObjectId> order = graph.walk(tip).toList();

            assertThat(order).doesNotHaveDuplicates();
            assertThat(order).containsExactlyInAnyOrderElementsOf(graph.bfs(tip));
        }

        @Test
        void theStartCommitComesFirst() {
            ObjectId tip = chain(4);

            assertThat(graph.walk(tip).findFirst()).contains(tip);
        }
    }

    @Nested
    @DisplayName("laziness")
    class Laziness {

        @Test
        void takingOnePageDoesNotReadTheWholeHistory() {
            // The claim paging rests on. Two hundred commits, thirty wanted.
            ObjectId tip = chain(200);
            counting.resetCounts();

            List<ObjectId> page = graph.walk(tip).limit(30).toList();

            assertThat(page).hasSize(30);
            assertThat(counting.readCount())
                    .as("reads for one page of a 200-commit history")
                    .isLessThanOrEqualTo(31);
        }

        @Test
        void bfsStillReadsEverythingBecauseItStillReturnsEverything() {
            /* The counterpart. bfs is not lazy and is not meant to be — it
               answers a different question, and RepositoryStatistics and
               contributions both need the whole set. */
            ObjectId tip = chain(50);
            counting.resetCounts();

            List<ObjectId> all = graph.bfs(tip);

            assertThat(all).hasSize(50);
            assertThat(counting.readCount()).isEqualTo(50);
        }

        @Test
        void notConsumingTheStreamReadsNothing() {
            ObjectId tip = chain(40);
            counting.resetCounts();

            graph.walk(tip);

            assertThat(counting.readCount()).isZero();
        }

        @Test
        void skippingIntoTheHistoryReadsOnlyAsFarAsItGoes() {
            // What a later page costs: the commits before it, and its own. Not
            // the tail beyond it.
            ObjectId tip = chain(120);
            counting.resetCounts();

            List<ObjectId> page = graph.walk(tip).skip(30).limit(10).toList();

            assertThat(page).hasSize(10);
            assertThat(counting.readCount()).isLessThanOrEqualTo(41);
        }
    }

    @Nested
    @DisplayName("exhaustion")
    class Exhaustion {

        @Test
        void aWalkEndsAtTheRootCommit() {
            ObjectId tip = chain(3);

            assertThat(graph.walk(tip).toList()).hasSize(3);
        }

        @Test
        void askingBeyondTheEndYieldsNothingRatherThanRepeating() {
            ObjectId root = commit("only");

            assertThat(graph.walk(root).limit(10).toList()).containsExactly(root);
        }

        @Test
        void refusesANullStart() {
            assertThatThrownBy(() -> graph.walk(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
