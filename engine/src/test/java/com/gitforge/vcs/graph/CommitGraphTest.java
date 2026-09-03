package com.gitforge.vcs.graph;

import com.gitforge.vcs.GoldenVectors;
import com.gitforge.vcs.InMemoryObjectStore;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.CorruptObjectException;
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

class CommitGraphTest {

    private static final ObjectId TREE = ObjectId.fromHex(GoldenVectors.TREE_ROOT);

    private InMemoryObjectStore store;
    private CommitGraph graph;
    private int sequence;

    @BeforeEach
    void setUp() {
        store = new InMemoryObjectStore();
        graph = new CommitGraph(store);
        sequence = 0;
    }

    /**
     * Creates and stores a commit. The message is made unique so that two
     * commits with the same parents still get distinct ids.
     */
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

    @Nested
    @DisplayName("breadth-first traversal")
    class Bfs {

        @Test
        void initialCommitAloneYieldsItself() {
            ObjectId root = commit("root");

            assertThat(graph.bfs(root)).containsExactly(root);
        }

        @Test
        void linearHistoryIsWalkedNewestToOldest() {
            //  a <- b <- c
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);

            assertThat(graph.bfs(c)).containsExactly(c, b, a);
        }

        @Test
        void branchingHistoryIsWalkedLevelByLevel() {
            //        d
            //       /
            //  a - b
            //       \
            //        c
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", b);

            // From a tip, only that tip's own ancestry is reachable.
            assertThat(graph.bfs(d)).containsExactly(d, b, a);
            assertThat(graph.bfs(c)).containsExactly(c, b, a);
        }

        @Test
        void mergeHistoryVisitsBothParentsBeforeTheirSharedAncestor() {
            //        c
            //       / \
            //  a - b   e
            //       \ /
            //        d
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", b);
            ObjectId e = commit("e", c, d);

            // Level order: the merge, then both parents in declared order, then
            // the ancestor they share, then its parent.
            assertThat(graph.bfs(e)).containsExactly(e, c, d, b, a);
        }

        @Test
        void aSharedAncestorAppearsExactlyOnce() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", a);
            ObjectId merge = commit("merge", b, c);

            assertThat(graph.bfs(merge)).containsExactly(merge, b, c, a).doesNotHaveDuplicates();
        }

        @Test
        void deepDiamondChainsDoNotExplode() {
            // Without a visited set the work here doubles per diamond.
            ObjectId current = commit("base");
            for (int i = 0; i < 12; i++) {
                ObjectId left = commit("left" + i, current);
                ObjectId right = commit("right" + i, current);
                current = commit("merge" + i, left, right);
            }

            // 1 base + 12 * (left, right, merge).
            assertThat(graph.bfs(current)).hasSize(37).doesNotHaveDuplicates();
        }

        @Test
        void rejectsANullStart() {
            assertThatThrownBy(() -> graph.bfs(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("depth-first traversal")
    class Dfs {

        @Test
        void initialCommitAloneYieldsItself() {
            ObjectId root = commit("root");

            assertThat(graph.dfs(root)).containsExactly(root);
        }

        @Test
        void linearHistoryMatchesBreadthFirstOrder() {
            // With no branching there is only one order to produce.
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);

            assertThat(graph.dfs(c)).containsExactly(c, b, a).isEqualTo(graph.bfs(c));
        }

        @Test
        void followsTheFirstParentToTheEndBeforeTheSecond() {
            //        c
            //       / \
            //  a - b   e
            //       \ /
            //        d
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", b);
            ObjectId e = commit("e", c, d);

            // Depth first: down the first-parent line (c, b, a) before d.
            // Breadth first would have produced e, c, d, b, a.
            assertThat(graph.dfs(e)).containsExactly(e, c, b, a, d);
            assertThat(graph.dfs(e)).isNotEqualTo(graph.bfs(e));
        }

        @Test
        void branchingHistoryReachesOnlyTheStartingTipsAncestry() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", b);

            assertThat(graph.dfs(c)).containsExactly(c, b, a).doesNotContain(d);
        }

        @Test
        void aSharedAncestorAppearsExactlyOnce() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", a);
            ObjectId merge = commit("merge", b, c);

            assertThat(graph.dfs(merge)).containsExactlyInAnyOrder(merge, b, c, a).doesNotHaveDuplicates();
        }

        @Test
        void reachesTheSameCommitsAsBreadthFirst() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", b);
            ObjectId e = commit("e", c, d);

            // The orders differ; the reachable set must not.
            assertThat(graph.dfs(e)).containsExactlyInAnyOrderElementsOf(graph.bfs(e));
        }

        @Test
        void handlesHistoryTooDeepForRecursion() {
            // An explicit stack is used precisely so this cannot overflow.
            ObjectId current = commit("root");
            for (int i = 0; i < 20_000; i++) {
                current = commit("c" + i, current);
            }

            assertThat(graph.dfs(current)).hasSize(20_001);
        }
    }

    @Nested
    @DisplayName("reachability")
    class Reachability {

        @Test
        void aCommitIsItsOwnAncestor() {
            ObjectId a = commit("a");

            assertThat(graph.isAncestor(a, a)).isTrue();
        }

        @Test
        void directParentIsAnAncestor() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);

            assertThat(graph.isAncestor(a, b)).isTrue();
        }

        @Test
        void distantAncestorIsReachable() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", c);

            assertThat(graph.isAncestor(a, d)).isTrue();
        }

        @Test
        void theRelationIsDirectional() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);

            assertThat(graph.isAncestor(a, b)).isTrue();
            assertThat(graph.isAncestor(b, a)).isFalse();
        }

        @Test
        void commitsOnSiblingBranchesAreNotAncestors() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", a);

            assertThat(graph.isAncestor(b, c)).isFalse();
            assertThat(graph.isAncestor(c, b)).isFalse();
        }

        @Test
        void unrelatedHistoriesShareNoAncestry() {
            ObjectId a = commit("a");
            ObjectId independent = commit("independent");

            assertThat(graph.isAncestor(a, independent)).isFalse();
            assertThat(graph.isAncestor(independent, a)).isFalse();
        }

        @Test
        void bothSidesOfAMergeAreAncestorsOfIt() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", a);
            ObjectId merge = commit("merge", b, c);

            assertThat(graph.isAncestor(b, merge)).isTrue();
            assertThat(graph.isAncestor(c, merge)).isTrue();
            assertThat(graph.isAncestor(a, merge)).isTrue();
            assertThat(graph.isAncestor(merge, b)).isFalse();
        }

        @Test
        void rejectsNullArguments() {
            ObjectId a = commit("a");

            assertThatThrownBy(() -> graph.isAncestor(null, a)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> graph.isAncestor(a, null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("common ancestors")
    class CommonAncestors {

        @Test
        void inLinearHistoryTheOlderCommitIsTheBase() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);

            assertThat(graph.findCommonAncestor(a, c)).contains(a);
            assertThat(graph.findCommonAncestor(b, c)).contains(b);
        }

        @Test
        void twoBranchesShareTheirForkPoint() {
            //      c
            //     /
            //  a-b
            //     \
            //      d
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", b);

            assertThat(graph.findCommonAncestor(c, d)).contains(b);
        }

        @Test
        void theLowestCommonAncestorIsChosenNotJustAnyCommonOne() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", b);

            // Both a and b are common ancestors; b is the lower and must win.
            assertThat(graph.mergeBases(c, d)).containsExactly(b);
        }

        @Test
        void unrelatedHistoriesHaveNoCommonAncestor() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId independent = commit("independent");

            assertThat(graph.findCommonAncestor(b, independent)).isEmpty();
            assertThat(graph.mergeBases(b, independent)).isEmpty();
        }

        @Test
        void aCommitIsItsOwnCommonAncestor() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);

            assertThat(graph.findCommonAncestor(b, b)).contains(b);
        }

        @Test
        void resultDoesNotDependOnArgumentOrder() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", b);

            assertThat(graph.mergeBases(c, d)).isEqualTo(graph.mergeBases(d, c));
            assertThat(graph.findCommonAncestor(c, d)).isEqualTo(graph.findCommonAncestor(d, c));
        }

        @Test
        void findsTheBaseAcrossAMergeCommit() {
            //  a - b - c ------ f
            //       \         /
            //        d - e ---
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", b);
            ObjectId d = commit("d", b);
            ObjectId e = commit("e", d);
            ObjectId f = commit("f", c, e);
            ObjectId g = commit("g", c);

            // f already contains c, so c itself is the base with g's line.
            assertThat(graph.findCommonAncestor(f, g)).contains(c);
        }

        @Test
        void crissCrossHistoryReportsEveryLowestBase() {
            //  Two branches that each merged the other: b and c are both
            //  lowest common ancestors of the two merges, and neither is an
            //  ancestor of the other.
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", a);
            ObjectId left = commit("left", b, c);
            ObjectId right = commit("right", c, b);

            List<ObjectId> bases = graph.mergeBases(left, right);

            assertThat(bases).containsExactlyInAnyOrder(b, c);
            // Sorted by object id, so the answer is stable and argument-order free.
            assertThat(bases).isSorted();
            assertThat(graph.findCommonAncestor(left, right)).contains(bases.getFirst());
        }

        @Test
        void aMergeBaseIsNeverAnAncestorOfAnotherMergeBase() {
            ObjectId a = commit("a");
            ObjectId b = commit("b", a);
            ObjectId c = commit("c", a);
            ObjectId left = commit("left", b, c);
            ObjectId right = commit("right", c, b);

            List<ObjectId> bases = graph.mergeBases(left, right);

            for (ObjectId base : bases) {
                for (ObjectId other : bases) {
                    if (!base.equals(other)) {
                        assertThat(graph.isAncestor(base, other)).isFalse();
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("malformed graphs")
    class Malformed {

        @Test
        void traversalTerminatesOnAForgedCycle() {
            // A real cycle cannot be built through the normal API: a commit's id
            // hashes bytes containing its parents' ids, so a cycle would need a
            // SHA-1 preimage. Forging one directly is the only way to prove the
            // visited set is what guarantees termination.
            ObjectId placeholder = ObjectId.fromHex("11".repeat(20));

            Signature author = Signature.of("Ada", "ada@example.com",
                    Instant.ofEpochSecond(GoldenVectors.SIGNATURE_EPOCH_SECONDS));
            Commit selfReferencing = Commit.of(TREE, List.of(placeholder), author, "cycle");

            // Filed under the id it names as its own parent.
            store.forge(placeholder, selfReferencing);

            assertThat(graph.bfs(placeholder)).containsExactly(placeholder);
            assertThat(graph.dfs(placeholder)).containsExactly(placeholder);
            assertThat(graph.isAncestor(placeholder, placeholder)).isTrue();
        }

        @Test
        void traversalTerminatesOnALongerForgedCycle() {
            ObjectId slotA = ObjectId.fromHex("aa".repeat(20));
            ObjectId slotB = ObjectId.fromHex("bb".repeat(20));

            Signature author = Signature.of("Ada", "ada@example.com",
                    Instant.ofEpochSecond(GoldenVectors.SIGNATURE_EPOCH_SECONDS));

            store.forge(slotA, Commit.of(TREE, List.of(slotB), author, "a"));
            store.forge(slotB, Commit.of(TREE, List.of(slotA), author, "b"));

            assertThat(graph.bfs(slotA)).containsExactly(slotA, slotB);
            assertThat(graph.dfs(slotA)).containsExactly(slotA, slotB);
        }

        @Test
        void aMissingParentIsReportedRatherThanSkipped() {
            ObjectId absent = ObjectId.fromHex("00".repeat(20));
            Signature author = Signature.of("Ada", "ada@example.com",
                    Instant.ofEpochSecond(GoldenVectors.SIGNATURE_EPOCH_SECONDS));

            Commit orphan = Commit.of(TREE, List.of(absent), author, "references a missing parent");
            store.write(orphan);

            assertThatThrownBy(() -> graph.bfs(orphan.id()))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("missing");
        }

        @Test
        void anIdThatIsNotACommitIsRejected() {
            ObjectId blobId = store.write(new com.gitforge.vcs.object.Blob("not a commit".getBytes()));

            assertThatThrownBy(() -> graph.bfs(blobId))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("not a commit");
        }

        @Test
        void rejectsANullStore() {
            assertThatThrownBy(() -> new CommitGraph(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
