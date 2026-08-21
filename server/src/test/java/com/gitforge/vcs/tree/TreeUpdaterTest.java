package com.gitforge.vcs.tree;

import com.gitforge.vcs.CountingObjectStore;
import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.storage.FileSystemObjectStore;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreeUpdaterTest {

    @TempDir
    Path tempDir;

    private CountingObjectStore store;
    private TreeUpdater updater;
    private TreeWalker walker;

    @BeforeEach
    void setUp() {
        store = new CountingObjectStore(new FileSystemObjectStore(tempDir));
        updater = new TreeUpdater(store);
        walker = new TreeWalker(store);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private ObjectId blob(String content) {
        return store.write(new Blob(bytes(content)));
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

    private ObjectId subtreeOf(ObjectId root, String name) {
        return store.readTree(root).entry(name).orElseThrow().id();
    }

    private PathUpdate put(String path, String content) {
        return PathUpdate.put(path, FileMode.REGULAR_FILE, blob(content));
    }

    @Nested
    @DisplayName("sparse rebuilding")
    class Sparse {

        @Test
        void anUntouchedSubtreeKeepsItsExactId() {
            ObjectId base = tree("src/App.java", "app\n", "docs/guide.md", "guide\n");
            ObjectId originalDocs = subtreeOf(base, "docs");

            ObjectId updated = updater.apply(base, List.of(put("src/App.java", "changed\n")));

            assertThat(subtreeOf(updated, "docs")).isEqualTo(originalDocs);
        }

        @Test
        void anUntouchedSubtreeIsNeverRead() {
            ObjectId base = tree("src/App.java", "app\n", "docs/deep/guide.md", "guide\n");
            ObjectId untouchedDocs = subtreeOf(base, "docs");
            store.resetCounts();

            updater.apply(base, List.of(put("src/App.java", "changed\n")));

            // Neither docs/ nor the deep/ subtree inside it is loaded.
            assertThat(store.hasRead(untouchedDocs)).isFalse();
        }

        @Test
        void onlyDirectoriesAlongTheChangedPathAreRewritten() {
            ObjectId base = tree(
                    "a/one.txt", "one\n",
                    "b/two.txt", "two\n",
                    "c/three.txt", "three\n",
                    "c/deep/four.txt", "four\n");
            store.resetCounts();

            updater.apply(base, List.of(put("c/deep/four.txt", "changed\n")));

            // The new blob, plus exactly three rebuilt trees: c/deep, c, and root.
            assertThat(store.writeCount()).isEqualTo(4);
        }

        @Test
        void costDoesNotGrowWithUntouchedBreadth() {
            TreeBuilder builder = new TreeBuilder(store);
            for (int i = 0; i < 50; i++) {
                builder.addFile("module" + i + "/file.txt", bytes("content " + i + "\n"));
            }
            ObjectId base = builder.build();
            store.resetCounts();

            updater.apply(base, List.of(put("module7/file.txt", "changed\n")));

            // Reads: the root and module7 only, regardless of the other 49.
            assertThat(store.readCount()).isLessThanOrEqualTo(2);
        }

        @Test
        void applyingNoUpdatesReturnsTheSameTree() {
            ObjectId base = tree("a.txt", "a\n");
            store.resetCounts();

            assertThat(updater.apply(base, List.of())).isEqualTo(base);
            assertThat(store.readCount()).isZero();
        }

        @Test
        void rewritingIdenticalContentYieldsTheSameTree() {
            ObjectId base = tree("a.txt", "a\n", "b.txt", "b\n");

            // Content addressing means an update that changes nothing produces
            // exactly the original tree.
            assertThat(updater.apply(base, List.of(put("a.txt", "a\n")))).isEqualTo(base);
        }
    }

    @Nested
    @DisplayName("applying changes")
    class Changes {

        @Test
        void addsANewFile() {
            ObjectId base = tree("a.txt", "a\n");

            ObjectId updated = updater.apply(base, List.of(put("b.txt", "b\n")));

            assertThat(contentsOf(updated)).containsOnly(
                    Map.entry("a.txt", "a\n"), Map.entry("b.txt", "b\n"));
        }

        @Test
        void addsAFileInANewNestedDirectory() {
            ObjectId base = tree("a.txt", "a\n");

            ObjectId updated = updater.apply(base, List.of(put("x/y/z/deep.txt", "deep\n")));

            assertThat(contentsOf(updated)).containsEntry("x/y/z/deep.txt", "deep\n");
        }

        @Test
        void modifiesAnExistingFile() {
            ObjectId base = tree("a.txt", "old\n");

            ObjectId updated = updater.apply(base, List.of(put("a.txt", "new\n")));

            assertThat(contentsOf(updated)).containsExactly(Map.entry("a.txt", "new\n"));
        }

        @Test
        void removesAFile() {
            ObjectId base = tree("a.txt", "a\n", "b.txt", "b\n");

            ObjectId updated = updater.apply(base, List.of(PathUpdate.remove("b.txt")));

            assertThat(contentsOf(updated)).containsExactly(Map.entry("a.txt", "a\n"));
        }

        @Test
        void appliesAdditionsModificationsAndRemovalsTogether() {
            ObjectId base = tree("keep.txt", "keep\n", "edit.txt", "old\n", "gone.txt", "bye\n");

            ObjectId updated = updater.apply(base, List.of(
                    put("edit.txt", "new\n"),
                    PathUpdate.remove("gone.txt"),
                    put("fresh.txt", "hi\n")));

            assertThat(contentsOf(updated)).containsOnly(
                    Map.entry("keep.txt", "keep\n"),
                    Map.entry("edit.txt", "new\n"),
                    Map.entry("fresh.txt", "hi\n"));
        }

        @Test
        void removingTheLastFileInADirectoryRemovesTheDirectory() {
            ObjectId base = tree("keep.txt", "keep\n", "doomed/only.txt", "only\n");

            ObjectId updated = updater.apply(base, List.of(PathUpdate.remove("doomed/only.txt")));

            assertThat(contentsOf(updated)).containsExactly(Map.entry("keep.txt", "keep\n"));
            assertThat(store.readTree(updated).entry("doomed")).isEmpty();
        }

        @Test
        void removingNestedFilesCollapsesEveryEmptiedLevel() {
            ObjectId base = tree("keep.txt", "keep\n", "a/b/c/only.txt", "only\n");

            ObjectId updated = updater.apply(base, List.of(PathUpdate.remove("a/b/c/only.txt")));

            assertThat(store.readTree(updated).entry("a")).isEmpty();
        }

        @Test
        void removingEverythingYieldsTheEmptyTree() {
            ObjectId base = tree("a.txt", "a\n");

            assertThat(updater.apply(base, List.of(PathUpdate.remove("a.txt"))))
                    .isEqualTo(Tree.empty().id());
        }

        @Test
        void buildsOntoTheEmptyTree() {
            ObjectId updated = updater.apply(Tree.empty().id(), List.of(
                    put("README.md", "# New\n"),
                    put("src/App.java", "app\n")));

            assertThat(contentsOf(updated)).containsOnly(
                    Map.entry("README.md", "# New\n"),
                    Map.entry("src/App.java", "app\n"));
        }

        @Test
        void preservesFileMode() {
            ObjectId base = tree("a.txt", "a\n");

            ObjectId updated = updater.apply(base, List.of(
                    PathUpdate.put("run.sh", FileMode.EXECUTABLE_FILE, blob("#!/bin/sh\n"))));

            assertThat(store.readTree(updated).entry("run.sh")).get()
                    .extracting(entry -> entry.mode()).isEqualTo(FileMode.EXECUTABLE_FILE);
        }

        @Test
        void handlesBinaryContent() {
            byte[] binary = new byte[256];
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) i;
            }
            ObjectId blobId = store.write(new Blob(binary));

            ObjectId updated = updater.apply(Tree.empty().id(),
                    List.of(PathUpdate.put("data.bin", FileMode.REGULAR_FILE, blobId)));

            ObjectId stored = walker.flatten(updated).getFirst().id();
            assertThat(store.readBlob(stored).payload()).isEqualTo(binary);
        }

        @Test
        void resultIsDeterministicRegardlessOfUpdateOrder() {
            ObjectId base = tree("a.txt", "a\n", "b.txt", "b\n");

            ObjectId forwards = updater.apply(base, List.of(put("a.txt", "A\n"), put("b.txt", "B\n")));
            ObjectId backwards = updater.apply(base, List.of(put("b.txt", "B\n"), put("a.txt", "A\n")));

            assertThat(forwards).isEqualTo(backwards);
        }
    }

    @Nested
    @DisplayName("rejected updates")
    class Rejected {

        @Test
        void refusesToRemoveAPathThatDoesNotExist() {
            ObjectId base = tree("a.txt", "a\n");

            assertThatThrownBy(() -> updater.apply(base, List.of(PathUpdate.remove("ghost.txt"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
        }

        @Test
        void refusesToWriteAFileOverADirectory() {
            ObjectId base = tree("src/App.java", "app\n");

            assertThatThrownBy(() -> updater.apply(base, List.of(put("src", "now a file\n"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is a directory");
        }

        @Test
        void refusesToDescendThroughAFile() {
            ObjectId base = tree("notes.txt", "notes\n");

            assertThatThrownBy(() -> updater.apply(base, List.of(put("notes.txt/inner.txt", "x\n"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is a file");
        }

        @Test
        void refusesAPathUsedAsBothFileAndDirectory() {
            assertThatThrownBy(() -> updater.apply(Tree.empty().id(), List.of(
                    put("thing", "a file\n"),
                    put("thing/inner.txt", "inside\n"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("both a file and a directory");
        }

        @Test
        void refusesPathTraversal() {
            ObjectId base = tree("a.txt", "a\n");

            assertThatThrownBy(() -> updater.apply(base, List.of(put("../escape.txt", "x\n"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'.' or '..'");
            assertThatThrownBy(() -> updater.apply(base, List.of(put("/absolute.txt", "x\n"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("relative");
        }

        @Test
        void refusesEmptyOrMalformedPaths() {
            ObjectId base = tree("a.txt", "a\n");

            assertThatThrownBy(() -> updater.apply(base, List.of(put("", "x\n"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> updater.apply(base, List.of(put("a//b.txt", "x\n"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty segments");
        }

        @Test
        void refusesADirectoryModeOnAFileUpdate() {
            assertThatThrownBy(() -> PathUpdate.put("x", FileMode.DIRECTORY, blob("x")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> new TreeUpdater(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> updater.apply(null, List.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> updater.apply(Tree.empty().id(), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
