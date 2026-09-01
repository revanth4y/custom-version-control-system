package com.gitforge.vcs.merge;

import com.gitforge.vcs.CountingObjectStore;
import com.gitforge.vcs.diff.TreeChange;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.tree.TreeBuilder;
import com.gitforge.vcs.tree.TreeWalker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the line-level merge meets the tree.
 *
 * <p>{@link LineMergerTest} pins the algorithm over three strings; this pins the
 * decisions around it — when it is asked at all, what happens to the blob it
 * produces, and which of the engine's existing promises must survive it.
 */
class ThreeWayMergerLineMergeTest {

    /** Deliberately far apart: one untouched line between edits is the rule. */
    private static final String BASE = "one\ntwo\nthree\nfour\nfive\n";
    private static final String OURS = "OURS\ntwo\nthree\nfour\nfive\n";
    private static final String THEIRS = "one\ntwo\nthree\nfour\nTHEIRS\n";
    private static final String MERGED = "OURS\ntwo\nthree\nfour\nTHEIRS\n";

    @TempDir
    Path tempDir;

    private CountingObjectStore store;
    private ThreeWayMerger merger;
    private TreeWalker walker;

    @BeforeEach
    void setUp() {
        store = new CountingObjectStore(new FileSystemObjectStore(tempDir));
        merger = new ThreeWayMerger(store);
        walker = new TreeWalker(store);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private ObjectId tree(Object... pathsAndContents) {
        TreeBuilder builder = new TreeBuilder(store);
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            builder.addFile((String) pathsAndContents[i], bytes((String) pathsAndContents[i + 1]));
        }
        return builder.build();
    }

    private Map<String, String> contentsOf(ObjectId treeId) {
        return walker.flatten(treeId).stream().collect(Collectors.toMap(
                TreeWalker.Entry::path,
                entry -> new String(store.readBlob(entry.id()).payload(), StandardCharsets.UTF_8)));
    }

    private ObjectId cleanTree(MergeResult result) {
        assertThat(result).isInstanceOf(MergeResult.Clean.class);
        return ((MergeResult.Clean) result).tree();
    }

    private MergeResult.Conflicted conflicted(MergeResult result) {
        assertThat(result).isInstanceOf(MergeResult.Conflicted.class);
        return (MergeResult.Conflicted) result;
    }

    @Nested
    @DisplayName("a file both sides changed")
    class BothSidesChanged {

        @Test
        void editsAtOppositeEndsOfOneFileNoLongerConflict() {
            ObjectId base = tree("shared.txt", BASE);
            ObjectId ours = tree("shared.txt", OURS);
            ObjectId theirs = tree("shared.txt", THEIRS);

            // Before this, two different file ids were all the tree merge could
            // see, and it had no choice but to call it a conflict.
            MergeResult result = merger.merge(base, ours, theirs);

            assertThat(contentsOf(cleanTree(result))).containsExactly(Map.entry("shared.txt", MERGED));
        }

        @Test
        void theMergedBlobIsStoredAndReHashesToItsOwnId() {
            ObjectId merged = cleanTree(merger.merge(
                    tree("shared.txt", BASE), tree("shared.txt", OURS), tree("shared.txt", THEIRS)));

            ObjectId blob = store.readTree(merged).entry("shared.txt").orElseThrow().id();

            assertThat(store.contains(blob)).isTrue();
            store.verify(blob);
        }

        @Test
        void theFilesModeIsCarriedThroughRatherThanInvented() {
            byte[] baseBytes = bytes(BASE);
            ObjectId base = new TreeBuilder(store)
                    .addFile("run.sh", baseBytes, FileMode.EXECUTABLE_FILE).build();
            ObjectId ours = new TreeBuilder(store)
                    .addFile("run.sh", bytes(OURS), FileMode.EXECUTABLE_FILE).build();
            ObjectId theirs = new TreeBuilder(store)
                    .addFile("run.sh", bytes(THEIRS), FileMode.EXECUTABLE_FILE).build();

            ObjectId result = cleanTree(merger.merge(base, ours, theirs));

            assertThat(store.readTree(result).entry("run.sh")).get()
                    .extracting(entry -> entry.mode()).isEqualTo(FileMode.EXECUTABLE_FILE);
        }

        @Test
        void aFileDeepInTheTreeIsMergedTheSameWay() {
            ObjectId base = tree("src/main/App.java", BASE);
            ObjectId ours = tree("src/main/App.java", OURS);
            ObjectId theirs = tree("src/main/App.java", THEIRS);

            assertThat(contentsOf(cleanTree(merger.merge(base, ours, theirs))))
                    .containsExactly(Map.entry("src/main/App.java", MERGED));
        }

        @Test
        void overlappingEditsAreStillAContentConflict() {
            ObjectId base = tree("shared.txt", "one\ntwo\nthree\n");
            ObjectId ours = tree("shared.txt", "one\nOURS\nthree\n");
            ObjectId theirs = tree("shared.txt", "one\nTHEIRS\nthree\n");

            List<MergeConflict> conflicts = conflicted(merger.merge(base, ours, theirs)).conflicts();

            assertThat(conflicts).singleElement().satisfies(conflict -> {
                assertThat(conflict.kind()).isEqualTo(ConflictKind.CONTENT);
                assertThat(conflict.path()).isEqualTo("shared.txt");
                assertThat(conflict.regions()).singleElement()
                        .extracting(ConflictRegion::base).isEqualTo(new LineRange(2, 3));
            });
        }
    }

    @Nested
    @DisplayName("what is left alone")
    class LeftAlone {

        @Test
        void binaryContentIsNotMergedLineByLine() {
            // A NUL byte is what makes it binary, and lines mean nothing in it.
            ObjectId base = new TreeBuilder(store)
                    .addFile("image.bin", new byte[] {0, 1, 2, 3}).build();
            ObjectId ours = new TreeBuilder(store)
                    .addFile("image.bin", new byte[] {0, 1, 2, 9}).build();
            ObjectId theirs = new TreeBuilder(store)
                    .addFile("image.bin", new byte[] {0, 1, 2, 7}).build();

            MergeConflict conflict = conflicted(merger.merge(base, ours, theirs)).conflicts().getFirst();

            assertThat(conflict.kind()).isEqualTo(ConflictKind.CONTENT);
            // Empty says "not established", not "nothing conflicts".
            assertThat(conflict.regions()).isEmpty();
        }

        @Test
        void aModeTheSidesDisagreeOnStopsTheContentMerge() {
            // The lines would reconcile, but the mode would not, and there is no
            // defensible answer for which mode a merged file should carry.
            ObjectId base = new TreeBuilder(store)
                    .addFile("run.sh", bytes(BASE), FileMode.REGULAR_FILE).build();
            ObjectId ours = new TreeBuilder(store)
                    .addFile("run.sh", bytes(OURS), FileMode.EXECUTABLE_FILE).build();
            ObjectId theirs = new TreeBuilder(store)
                    .addFile("run.sh", bytes(THEIRS), FileMode.REGULAR_FILE).build();

            MergeConflict conflict = conflicted(merger.merge(base, ours, theirs)).conflicts().getFirst();

            assertThat(conflict.kind()).isEqualTo(ConflictKind.CONTENT);
            assertThat(conflict.regions()).isEmpty();
        }

        @Test
        void identicalContentWithDifferentModesIsStillAModeConflict() {
            ObjectId base = new TreeBuilder(store)
                    .addFile("run.sh", bytes("old\n"), FileMode.REGULAR_FILE).build();
            ObjectId ours = new TreeBuilder(store)
                    .addFile("run.sh", bytes("new\n"), FileMode.EXECUTABLE_FILE).build();
            ObjectId theirs = new TreeBuilder(store)
                    .addFile("run.sh", bytes("new\n"), FileMode.REGULAR_FILE).build();

            MergeConflict conflict = conflicted(merger.merge(base, ours, theirs)).conflicts().getFirst();

            assertThat(conflict.kind()).isEqualTo(ConflictKind.MODE);
            assertThat(conflict.regions()).isEmpty();
        }

        @Test
        void aFileAgainstADirectoryIsStillATypeConflict() {
            ObjectId base = tree("thing", BASE);
            ObjectId ours = tree("thing", OURS);
            ObjectId theirs = tree("thing/inside.txt", "inside\n");

            MergeConflict conflict = conflicted(merger.merge(base, ours, theirs)).conflicts().getFirst();

            assertThat(conflict.kind()).isEqualTo(ConflictKind.TYPE);
            assertThat(conflict.regions()).isEmpty();
        }

        @Test
        void aFileOneSideDeletedIsStillAModifyDeleteConflict() {
            ObjectId base = tree("a.txt", BASE, "keep.txt", "keep\n");
            ObjectId ours = tree("a.txt", OURS, "keep.txt", "keep\n");
            ObjectId theirs = tree("keep.txt", "keep\n");

            MergeConflict conflict = conflicted(merger.merge(base, ours, theirs)).conflicts().getFirst();

            assertThat(conflict.kind()).isEqualTo(ConflictKind.MODIFY_DELETE);
            assertThat(conflict.regions()).isEmpty();
        }

        @Test
        void aFileNeitherSideHadBeforeIsStillAnAddAddConflict() {
            // No base means nothing to measure the two against, so there is no
            // line-level question to ask.
            ObjectId base = tree("other.txt", "other\n");
            ObjectId ours = tree("other.txt", "other\n", "new.txt", OURS);
            ObjectId theirs = tree("other.txt", "other\n", "new.txt", THEIRS);

            MergeConflict conflict = conflicted(merger.merge(base, ours, theirs)).conflicts().getFirst();

            assertThat(conflict.kind()).isEqualTo(ConflictKind.ADD_ADD);
            assertThat(conflict.regions()).isEmpty();
        }

        @Test
        void aPathThatWasADirectoryInTheBaseIsNotLineMerged() {
            // Both sides replaced a directory with a file. There is no base file
            // to merge against, whatever the classification says.
            ObjectId base = tree("thing/inside.txt", "inside\n", "keep.txt", "keep\n");
            ObjectId ours = tree("thing", OURS, "keep.txt", "keep\n");
            ObjectId theirs = tree("thing", THEIRS, "keep.txt", "keep\n");

            MergeConflict conflict = conflicted(merger.merge(base, ours, theirs)).conflicts().getFirst();

            assertThat(conflict.path()).isEqualTo("thing");
            assertThat(conflict.regions()).isEmpty();
        }

        @Test
        void beyondTheBudgetFilesFallBackToPlainContentConflicts() {
            ThreeWayMerger budgeted = new ThreeWayMerger(store, 1);

            ObjectId base = tree("a.txt", BASE, "b.txt", BASE);
            ObjectId ours = tree("a.txt", OURS, "b.txt", OURS);
            ObjectId theirs = tree("a.txt", THEIRS, "b.txt", THEIRS);

            // The first file merges; the second is past the budget and is
            // reported as it would have been before any of this existed.
            MergeResult.Conflicted result = conflicted(budgeted.merge(base, ours, theirs));

            assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
                assertThat(conflict.path()).isEqualTo("b.txt");
                assertThat(conflict.kind()).isEqualTo(ConflictKind.CONTENT);
                assertThat(conflict.regions()).isEmpty();
            });
        }

        @Test
        void aBudgetOfZeroLeavesTheOldBehaviourExactlyAsItWas() {
            ThreeWayMerger none = new ThreeWayMerger(store, 0);

            MergeResult.Conflicted result = conflicted(none.merge(
                    tree("shared.txt", BASE), tree("shared.txt", OURS), tree("shared.txt", THEIRS)));

            assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
                assertThat(conflict.kind()).isEqualTo(ConflictKind.CONTENT);
                assertThat(conflict.regions()).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("the object store")
    class Store {

        @Test
        void aMergeThatConflictsElsewhereWritesNothingAtAll() {
            // shared.txt reconciles and produces a blob; collide.txt does not.
            // The blob must not survive the merge that never completed.
            ObjectId base = tree("shared.txt", BASE, "collide.txt", "a\n");
            ObjectId ours = tree("shared.txt", OURS, "collide.txt", "ours\n");
            ObjectId theirs = tree("shared.txt", THEIRS, "collide.txt", "theirs\n");

            store.resetCounts();
            MergeResult result = merger.merge(base, ours, theirs);

            assertThat(result.isClean()).isFalse();
            assertThat(store.writtenIds()).isEmpty();
        }

        @Test
        void theMergedFileIsStillReportedAmongWhatWouldHaveMerged() {
            ObjectId base = tree("shared.txt", BASE, "collide.txt", "a\n");
            ObjectId ours = tree("shared.txt", OURS, "collide.txt", "ours\n");
            ObjectId theirs = tree("shared.txt", THEIRS, "collide.txt", "theirs\n");

            MergeResult.Conflicted result = conflicted(merger.merge(base, ours, theirs));

            assertThat(result.cleanlyMerged()).extracting(TreeChange::path).contains("shared.txt");
        }

        @Test
        void aCleanMergeWritesTheBlobBeforeTheTreeThatNamesIt() {
            ObjectId merged = cleanTree(merger.merge(
                    tree("shared.txt", BASE), tree("shared.txt", OURS), tree("shared.txt", THEIRS)));

            ObjectId blob = store.readTree(merged).entry("shared.txt").orElseThrow().id();
            List<ObjectId> written = store.writtenIds();

            assertThat(written.indexOf(blob)).isGreaterThanOrEqualTo(0);
            assertThat(written.indexOf(blob)).isLessThan(written.indexOf(merged));
        }

        @Test
        void filesNeitherSideTouchedStillCostNothing() {
            ObjectId base = tree("shared.txt", BASE, "docs/guide.md", "guide\n");
            ObjectId ours = tree("shared.txt", OURS, "docs/guide.md", "guide\n");
            ObjectId theirs = tree("shared.txt", THEIRS, "docs/guide.md", "guide\n");

            store.resetCounts();
            merger.merge(base, ours, theirs);

            // The merged blob and the root tree; docs/ is reused untouched.
            assertThat(store.writtenIds()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        void aLineMergedResultIsTheSameWithItsArgumentsSwapped() {
            ObjectId base = tree("shared.txt", BASE);
            ObjectId ours = tree("shared.txt", OURS);
            ObjectId theirs = tree("shared.txt", THEIRS);

            assertThat(cleanTree(merger.merge(base, ours, theirs)))
                    .isEqualTo(cleanTree(merger.merge(base, theirs, ours)));
        }

        @Test
        void aLineLevelConflictIsTheSameWithItsArgumentsSwapped() {
            ObjectId base = tree("shared.txt", "one\ntwo\nthree\n");
            ObjectId ours = tree("shared.txt", "one\nOURS\nthree\n");
            ObjectId theirs = tree("shared.txt", "one\nTHEIRS\nthree\n");

            MergeConflict forward = conflicted(merger.merge(base, ours, theirs)).conflicts().getFirst();
            MergeConflict reversed = conflicted(merger.merge(base, theirs, ours)).conflicts().getFirst();

            assertThat(reversed.kind()).isEqualTo(forward.kind());
            assertThat(reversed.regions()).hasSameSizeAs(forward.regions());
            assertThat(reversed.regions().getFirst().base())
                    .isEqualTo(forward.regions().getFirst().base());
        }

        @Test
        void repeatingALineMergeProducesTheSameTree() {
            ObjectId base = tree("shared.txt", BASE);
            ObjectId ours = tree("shared.txt", OURS);
            ObjectId theirs = tree("shared.txt", THEIRS);

            assertThat(cleanTree(merger.merge(base, ours, theirs)))
                    .isEqualTo(cleanTree(merger.merge(base, ours, theirs)));
        }

        @Test
        void aConflictNothingIsKnownAboutCarriesNoRegionsRatherThanEmptyOnes() {
            MergeConflict conflict = new MergeConflict(
                    ConflictKind.TYPE, "thing",
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());

            assertThat(conflict.regions()).isEmpty();
        }
    }
}
