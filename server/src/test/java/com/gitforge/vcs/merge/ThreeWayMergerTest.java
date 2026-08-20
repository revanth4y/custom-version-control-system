package com.gitforge.vcs.merge;

import com.gitforge.vcs.CountingObjectStore;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The merge outcome table: for every way two sides can relate to their base,
 * what the merge must produce.
 */
class ThreeWayMergerTest {

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

    /** The merged tree, flattened to path to content, for readable assertions. */
    private Map<String, String> contentsOf(ObjectId treeId) {
        return walker.flatten(treeId).stream().collect(Collectors.toMap(
                TreeWalker.Entry::path,
                entry -> new String(store.readBlob(entry.id()).payload(), StandardCharsets.UTF_8)));
    }

    private ObjectId cleanTree(MergeResult result) {
        assertThat(result).isInstanceOf(MergeResult.Clean.class);
        return ((MergeResult.Clean) result).tree();
    }

    private ObjectId subtreeOf(ObjectId root, String name) {
        return store.readTree(root).entry(name).orElseThrow().id();
    }

    @Nested
    @DisplayName("the outcome table")
    class Outcomes {

        @Test
        void unchangedOnBothSides() {
            ObjectId base = tree("a.txt", "same\n");

            MergeResult result = merger.merge(base, base, base);

            assertThat(cleanTree(result)).isEqualTo(base);
        }

        @Test
        void oursOnlyChangeIsKept() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId ours = tree("a.txt", "ours\n");

            MergeResult result = merger.merge(base, ours, base);

            assertThat(contentsOf(cleanTree(result))).containsExactly(Map.entry("a.txt", "ours\n"));
        }

        @Test
        void theirsOnlyChangeIsTaken() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId theirs = tree("a.txt", "theirs\n");

            MergeResult result = merger.merge(base, base, theirs);

            assertThat(contentsOf(cleanTree(result))).containsExactly(Map.entry("a.txt", "theirs\n"));
        }

        @Test
        void identicalChangesOnBothSidesMergeCleanly() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId changed = tree("a.txt", "both made this exact edit\n");

            // Both sides diverge from base, but agree with each other, so there
            // is nothing to disagree about.
            MergeResult result = merger.merge(base, changed, changed);

            assertThat(cleanTree(result)).isEqualTo(changed);
        }

        @Test
        void changesToDifferentFilesAreCombined() {
            ObjectId base = tree("a.txt", "a\n", "b.txt", "b\n");
            ObjectId ours = tree("a.txt", "ours edited a\n", "b.txt", "b\n");
            ObjectId theirs = tree("a.txt", "a\n", "b.txt", "theirs edited b\n");

            MergeResult result = merger.merge(base, ours, theirs);

            assertThat(contentsOf(cleanTree(result))).containsOnly(
                    Map.entry("a.txt", "ours edited a\n"),
                    Map.entry("b.txt", "theirs edited b\n"));
        }

        @Test
        void additionOnOneSideIsIncluded() {
            ObjectId base = tree("a.txt", "a\n");
            ObjectId ours = tree("a.txt", "a\n");
            ObjectId theirs = tree("a.txt", "a\n", "new.txt", "brand new\n");

            MergeResult result = merger.merge(base, ours, theirs);

            assertThat(contentsOf(cleanTree(result))).containsKey("new.txt");
        }

        @Test
        void identicalAdditionsOnBothSidesMergeCleanly() {
            ObjectId base = tree("a.txt", "a\n");
            ObjectId both = tree("a.txt", "a\n", "new.txt", "identical\n");

            MergeResult result = merger.merge(base, both, both);

            assertThat(contentsOf(cleanTree(result))).containsKey("new.txt");
        }

        @Test
        void deletionOnOneSideIsApplied() {
            ObjectId base = tree("a.txt", "a\n", "doomed.txt", "bye\n");
            ObjectId ours = tree("a.txt", "a\n", "doomed.txt", "bye\n");
            ObjectId theirs = tree("a.txt", "a\n");

            MergeResult result = merger.merge(base, ours, theirs);

            assertThat(contentsOf(cleanTree(result))).doesNotContainKey("doomed.txt");
        }

        @Test
        void deletionOnBothSidesIsApplied() {
            ObjectId base = tree("a.txt", "a\n", "doomed.txt", "bye\n");
            ObjectId withoutIt = tree("a.txt", "a\n");

            MergeResult result = merger.merge(base, withoutIt, withoutIt);

            assertThat(contentsOf(cleanTree(result))).doesNotContainKey("doomed.txt");
        }

        @Test
        void modeChangeOnOneSideIsKept() {
            ObjectId base = new TreeBuilder(store)
                    .addFile("run.sh", bytes("#!/bin/sh\n"), FileMode.REGULAR_FILE).build();
            ObjectId ours = new TreeBuilder(store)
                    .addFile("run.sh", bytes("#!/bin/sh\n"), FileMode.EXECUTABLE_FILE).build();

            MergeResult result = merger.merge(base, ours, base);

            assertThat(store.readTree(cleanTree(result)).entry("run.sh")).get()
                    .extracting(entry -> entry.mode()).isEqualTo(FileMode.EXECUTABLE_FILE);
        }

        @Test
        void directoriesChangedOnBothSidesAreMergedNotConflicted() {
            ObjectId base = tree("src/App.java", "app\n", "src/Util.java", "util\n");
            ObjectId ours = tree("src/App.java", "ours edited app\n", "src/Util.java", "util\n");
            ObjectId theirs = tree("src/App.java", "app\n", "src/Util.java", "theirs edited util\n");

            // Both changed src/, but a directory changed on both sides is just a
            // smaller instance of the same problem.
            MergeResult result = merger.merge(base, ours, theirs);

            assertThat(contentsOf(cleanTree(result))).containsOnly(
                    Map.entry("src/App.java", "ours edited app\n"),
                    Map.entry("src/Util.java", "theirs edited util\n"));
        }

        @Test
        void bothSidesCreatingTheSameDirectoryMergesItsContents() {
            ObjectId base = tree("a.txt", "a\n");
            ObjectId ours = tree("a.txt", "a\n", "new/ours.txt", "ours\n");
            ObjectId theirs = tree("a.txt", "a\n", "new/theirs.txt", "theirs\n");

            // Neither directory existed in the base, so they merge against nothing.
            MergeResult result = merger.merge(base, ours, theirs);

            assertThat(contentsOf(cleanTree(result))).containsOnly(
                    Map.entry("a.txt", "a\n"),
                    Map.entry("new/ours.txt", "ours\n"),
                    Map.entry("new/theirs.txt", "theirs\n"));
        }
    }

    @Nested
    @DisplayName("object reuse and short-circuiting")
    class Reuse {

        @Test
        void anUntouchedSubtreeKeepsItsExactIdInTheResult() {
            ObjectId base = tree("src/App.java", "app\n", "docs/guide.md", "guide\n");
            ObjectId ours = tree("src/App.java", "ours\n", "docs/guide.md", "guide\n");
            ObjectId theirs = tree("src/App.java", "app\n", "docs/guide.md", "guide\n",
                    "extra.txt", "extra\n");

            ObjectId originalDocs = subtreeOf(base, "docs");
            MergeResult result = merger.merge(base, ours, theirs);

            // Reused verbatim rather than rebuilt into an equal-but-new object.
            assertThat(subtreeOf(cleanTree(result), "docs")).isEqualTo(originalDocs);
        }

        @Test
        void anUntouchedSubtreeIsNeverRead() {
            ObjectId base = tree("src/App.java", "app\n", "docs/deep/guide.md", "guide\n");
            ObjectId ours = tree("src/App.java", "ours\n", "docs/deep/guide.md", "guide\n");
            ObjectId theirs = tree("src/App.java", "app\n", "docs/deep/guide.md", "guide\n",
                    "extra.txt", "extra\n");

            ObjectId untouchedDocs = subtreeOf(base, "docs");
            store.resetCounts();

            merger.merge(base, ours, theirs);

            assertThat(store.hasRead(untouchedDocs)).isFalse();
        }

        @Test
        void mergingWritesNoObjectsForUnchangedParts() {
            ObjectId base = tree("a.txt", "a\n", "docs/guide.md", "guide\n");
            ObjectId ours = tree("a.txt", "ours\n", "docs/guide.md", "guide\n");
            ObjectId theirs = tree("a.txt", "a\n", "docs/guide.md", "guide\n", "b.txt", "b\n");

            store.resetCounts();
            merger.merge(base, ours, theirs);

            // Only the root needs rebuilding; docs/ and every blob already exist.
            assertThat(store.writtenIds()).hasSize(1);
        }

        @Test
        void whollyAgreeingSidesShortCircuitWithoutReadingAnything() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId same = tree("a.txt", "changed\n");
            store.resetCounts();

            MergeResult result = merger.merge(base, same, same);

            assertThat(cleanTree(result)).isEqualTo(same);
            assertThat(store.readCount()).isZero();
        }

        @Test
        void anUnchangedSideShortCircuitsWithoutReadingAnything() {
            ObjectId base = tree("a.txt", "base\n");
            ObjectId theirs = tree("a.txt", "theirs\n");
            store.resetCounts();

            assertThat(cleanTree(merger.merge(base, base, theirs))).isEqualTo(theirs);
            assertThat(cleanTree(merger.merge(base, theirs, base))).isEqualTo(theirs);
            assertThat(store.readCount()).isZero();
        }
    }

    @Nested
    @DisplayName("structure and determinism")
    class Structure {

        @Test
        void aDirectoryEmptiedByBothSidesDisappears() {
            ObjectId base = tree("keep.txt", "keep\n", "doomed/a.txt", "a\n", "doomed/b.txt", "b\n");
            ObjectId ours = tree("keep.txt", "keep\n", "doomed/b.txt", "b\n");
            ObjectId theirs = tree("keep.txt", "keep\n", "doomed/a.txt", "a\n");

            MergeResult result = merger.merge(base, ours, theirs);
            ObjectId merged = cleanTree(result);

            // Each side removed one file; together the directory has nothing
            // left, and a tree describes directories only by their contents.
            assertThat(contentsOf(merged)).containsExactly(Map.entry("keep.txt", "keep\n"));
            assertThat(store.readTree(merged).entry("doomed")).isEmpty();
        }

        @Test
        void mergesSeveralLevelsDeep() {
            ObjectId base = tree("a/b/c/one.txt", "one\n", "a/b/c/two.txt", "two\n");
            ObjectId ours = tree("a/b/c/one.txt", "ours\n", "a/b/c/two.txt", "two\n");
            ObjectId theirs = tree("a/b/c/one.txt", "one\n", "a/b/c/two.txt", "theirs\n");

            assertThat(contentsOf(cleanTree(merger.merge(base, ours, theirs)))).containsOnly(
                    Map.entry("a/b/c/one.txt", "ours\n"),
                    Map.entry("a/b/c/two.txt", "theirs\n"));
        }

        @Test
        void mergesAgainstAnEmptyBase() {
            ObjectId empty = new TreeBuilder(store).build();
            ObjectId ours = tree("ours.txt", "ours\n");
            ObjectId theirs = tree("theirs.txt", "theirs\n");

            // Unrelated histories: nothing in common, but nothing in conflict.
            assertThat(contentsOf(cleanTree(merger.merge(empty, ours, theirs)))).containsOnly(
                    Map.entry("ours.txt", "ours\n"),
                    Map.entry("theirs.txt", "theirs\n"));
        }

        @Test
        void acceptsAnUnstoredEmptyTreeAsTheBase() {
            ObjectId ours = tree("ours.txt", "ours\n");
            ObjectId theirs = tree("theirs.txt", "theirs\n");

            assertThat(merger.merge(Tree.empty().id(), ours, theirs).isClean()).isTrue();
        }

        @Test
        void repeatingAMergeProducesTheSameTree() {
            ObjectId base = tree("a.txt", "a\n", "b.txt", "b\n");
            ObjectId ours = tree("a.txt", "ours\n", "b.txt", "b\n");
            ObjectId theirs = tree("a.txt", "a\n", "b.txt", "theirs\n");

            assertThat(cleanTree(merger.merge(base, ours, theirs)))
                    .isEqualTo(cleanTree(merger.merge(base, ours, theirs)));
        }

        @Test
        void aCleanMergeIsSymmetricInItsResultingTree() {
            ObjectId base = tree("a.txt", "a\n", "b.txt", "b\n");
            ObjectId ours = tree("a.txt", "ours\n", "b.txt", "b\n");
            ObjectId theirs = tree("a.txt", "a\n", "b.txt", "theirs\n");

            // When nothing conflicts, which side is "ours" cannot matter.
            assertThat(cleanTree(merger.merge(base, ours, theirs)))
                    .isEqualTo(cleanTree(merger.merge(base, theirs, ours)));
        }

        @Test
        void theMergedTreeIsAValidStoredObject() {
            ObjectId base = tree("a.txt", "a\n");
            ObjectId ours = tree("a.txt", "ours\n");
            ObjectId theirs = tree("a.txt", "a\n", "b.txt", "b\n");

            ObjectId merged = cleanTree(merger.merge(base, ours, theirs));

            assertThat(store.contains(merged)).isTrue();
            store.verify(merged);
            assertThat(store.readTree(merged).id()).isEqualTo(merged);
        }

        @Test
        void rejectsNullArguments() {
            ObjectId root = tree("a.txt", "a\n");

            assertThatThrownBy(() -> merger.merge(null, root, root)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> merger.merge(root, null, root)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> merger.merge(root, root, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ThreeWayMerger(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
