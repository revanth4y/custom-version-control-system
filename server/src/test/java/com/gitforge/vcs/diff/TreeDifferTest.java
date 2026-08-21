package com.gitforge.vcs.diff;

import com.gitforge.vcs.CountingObjectStore;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.tree.TreeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreeDifferTest {

    @TempDir
    Path tempDir;

    private CountingObjectStore store;
    private TreeDiffer differ;

    @BeforeEach
    void setUp() {
        store = new CountingObjectStore(new FileSystemObjectStore(tempDir));
        differ = new TreeDiffer(store);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Builds a tree from alternating path/content arguments. */
    private ObjectId tree(Object... pathsAndContents) {
        TreeBuilder builder = new TreeBuilder(store);
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            String path = (String) pathsAndContents[i];
            Object value = pathsAndContents[i + 1];
            if (value instanceof FileMode mode) {
                builder.addFile(path, bytes("executable"), mode);
            } else {
                builder.addFile(path, bytes((String) value));
            }
        }
        return builder.build();
    }

    private ObjectId subtreeOf(ObjectId root, String name) {
        return store.readTree(root).entry(name).orElseThrow().id();
    }

    @Nested
    @DisplayName("Merkle short-circuiting")
    class ShortCircuiting {

        @Test
        void identicalTreesCostNoReadsAtAll() {
            ObjectId root = tree(
                    "README.md", "# Demo\n",
                    "src/App.java", "class App {}\n",
                    "docs/guide.md", "# Guide\n");
            store.resetCounts();

            TreeDiff diff = differ.diff(root, root);

            assertThat(diff.isEmpty()).isTrue();
            // The ids match, so equality alone settles it: nothing is loaded.
            assertThat(store.readCount()).isZero();
        }

        @Test
        void anUntouchedSubtreeIsNeverRead() {
            ObjectId before = tree(
                    "src/App.java", "class App {}\n",
                    "docs/guide.md", "# Guide\n",
                    "docs/deep/notes.md", "notes\n");
            ObjectId after = tree(
                    "src/App.java", "class App { int x; }\n",
                    "docs/guide.md", "# Guide\n",
                    "docs/deep/notes.md", "notes\n");

            ObjectId untouchedDocs = subtreeOf(before, "docs");
            assertThat(untouchedDocs).isEqualTo(subtreeOf(after, "docs"));

            store.resetCounts();
            TreeDiff diff = differ.diff(before, after);

            assertThat(diff.paths()).containsExactly("src/App.java");
            // docs/ is provably unchanged, so neither it nor anything beneath it
            // is loaded — including the nested deep/ subtree.
            assertThat(store.hasRead(untouchedDocs)).isFalse();
        }

        @Test
        void costIsProportionalToWhatChangedNotToRepositorySize() {
            Map<String, String> wide = new LinkedHashMap<>();
            for (int i = 0; i < 40; i++) {
                wide.put("module" + i + "/file.txt", "content " + i + "\n");
            }

            TreeBuilder beforeBuilder = new TreeBuilder(store);
            wide.forEach((path, content) -> beforeBuilder.addFile(path, bytes(content)));
            ObjectId before = beforeBuilder.build();

            TreeBuilder afterBuilder = new TreeBuilder(store);
            wide.forEach((path, content) -> afterBuilder.addFile(path,
                    bytes(path.equals("module7/file.txt") ? "changed\n" : content)));
            ObjectId after = afterBuilder.build();

            store.resetCounts();
            TreeDiff diff = differ.diff(before, after);

            assertThat(diff.paths()).containsExactly("module7/file.txt");
            // Two roots and the two versions of the one changed directory.
            assertThat(store.readCount()).isLessThanOrEqualTo(4);
        }
    }

    @Nested
    @DisplayName("file-level changes")
    class Changes {

        @Test
        void detectsAnAddedFile() {
            ObjectId before = tree("a.txt", "a\n");
            ObjectId after = tree("a.txt", "a\n", "b.txt", "b\n");

            assertThat(differ.diff(before, after).added())
                    .singleElement()
                    .satisfies(added -> {
                        assertThat(added.path()).isEqualTo("b.txt");
                        assertThat(added.mode()).isEqualTo(FileMode.REGULAR_FILE);
                    });
        }

        @Test
        void detectsADeletedFile() {
            ObjectId before = tree("a.txt", "a\n", "b.txt", "b\n");
            ObjectId after = tree("a.txt", "a\n");

            assertThat(differ.diff(before, after).deleted())
                    .extracting(TreeChange.Deleted::path)
                    .containsExactly("b.txt");
        }

        @Test
        void detectsAContentChange() {
            ObjectId before = tree("a.txt", "old\n");
            ObjectId after = tree("a.txt", "new\n");

            assertThat(differ.diff(before, after).modified())
                    .singleElement()
                    .satisfies(modified -> {
                        assertThat(modified.path()).isEqualTo("a.txt");
                        assertThat(modified.isContentChange()).isTrue();
                        assertThat(modified.isModeChange()).isFalse();
                    });
        }

        @Test
        void detectsAModeOnlyChange() {
            // Identical bytes, so only the mode distinguishes the two trees.
            ObjectId before = new TreeBuilder(store)
                    .addFile("run.sh", bytes("#!/bin/sh\n"), FileMode.REGULAR_FILE).build();
            ObjectId after = new TreeBuilder(store)
                    .addFile("run.sh", bytes("#!/bin/sh\n"), FileMode.EXECUTABLE_FILE).build();

            assertThat(differ.diff(before, after).modified())
                    .singleElement()
                    .satisfies(modified -> {
                        assertThat(modified.isModeChange()).isTrue();
                        assertThat(modified.isContentChange()).isFalse();
                        assertThat(modified.oldBlob()).isEqualTo(modified.newBlob());
                    });
        }

        @Test
        void reportsAnAddedDirectoryAsItsFiles() {
            ObjectId before = tree("a.txt", "a\n");
            ObjectId after = tree("a.txt", "a\n", "src/App.java", "app\n", "src/deep/Util.java", "util\n");

            assertThat(differ.diff(before, after).added())
                    .extracting(TreeChange.Added::path)
                    .containsExactly("src/App.java", "src/deep/Util.java");
        }

        @Test
        void reportsADeletedDirectoryAsItsFiles() {
            ObjectId before = tree("a.txt", "a\n", "src/App.java", "app\n", "src/deep/Util.java", "util\n");
            ObjectId after = tree("a.txt", "a\n");

            assertThat(differ.diff(before, after).deleted())
                    .extracting(TreeChange.Deleted::path)
                    .containsExactly("src/App.java", "src/deep/Util.java");
        }

        @Test
        void reportsAFileBecomingADirectoryAsADeletionAndAdditions() {
            ObjectId before = tree("src", "I am a file\n");
            ObjectId after = tree("src/App.java", "app\n", "src/Util.java", "util\n");

            TreeDiff diff = differ.diff(before, after);

            assertThat(diff.deleted()).extracting(TreeChange.Deleted::path).containsExactly("src");
            assertThat(diff.added()).extracting(TreeChange.Added::path)
                    .containsExactly("src/App.java", "src/Util.java");
        }

        @Test
        void reportsADirectoryBecomingAFileAsDeletionsAndAnAddition() {
            ObjectId before = tree("src/App.java", "app\n");
            ObjectId after = tree("src", "I am a file\n");

            TreeDiff diff = differ.diff(before, after);

            assertThat(diff.deleted()).extracting(TreeChange.Deleted::path).containsExactly("src/App.java");
            assertThat(diff.added()).extracting(TreeChange.Added::path).containsExactly("src");
        }

        @Test
        void findsChangesSeveralLevelsDeep() {
            ObjectId before = tree("a/b/c/d/deep.txt", "before\n");
            ObjectId after = tree("a/b/c/d/deep.txt", "after\n");

            assertThat(differ.diff(before, after).paths()).containsExactly("a/b/c/d/deep.txt");
        }

        @Test
        void reportsSeveralKindsOfChangeTogether() {
            ObjectId before = tree("keep.txt", "same\n", "edit.txt", "old\n", "gone.txt", "bye\n");
            ObjectId after = tree("keep.txt", "same\n", "edit.txt", "new\n", "fresh.txt", "hi\n");

            TreeDiff diff = differ.diff(before, after);

            assertThat(diff.added()).extracting(TreeChange.Added::path).containsExactly("fresh.txt");
            assertThat(diff.deleted()).extracting(TreeChange.Deleted::path).containsExactly("gone.txt");
            assertThat(diff.modified()).extracting(TreeChange.Modified::path).containsExactly("edit.txt");
            assertThat(diff.size()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("edge cases and ordering")
    class EdgeCases {

        @Test
        void comparingAgainstAnEmptyTreeYieldsPureAdditions() {
            ObjectId empty = new TreeBuilder(store).build();
            ObjectId populated = tree("a.txt", "a\n", "src/App.java", "app\n");

            assertThat(differ.diff(empty, populated).added())
                    .extracting(TreeChange.Added::path)
                    .containsExactly("a.txt", "src/App.java");
        }

        @Test
        void comparingAnEmptyTreeTheOtherWayYieldsPureDeletions() {
            ObjectId empty = new TreeBuilder(store).build();
            ObjectId populated = tree("a.txt", "a\n", "src/App.java", "app\n");

            assertThat(differ.diff(populated, empty).deleted())
                    .extracting(TreeChange.Deleted::path)
                    .containsExactly("a.txt", "src/App.java");
        }

        @Test
        void handlesAnUnstoredEmptyTreeId() {
            // The empty tree's contents are fixed and known, so it need not have
            // been written for a comparison against it to work.
            ObjectId populated = tree("a.txt", "a\n");

            assertThat(differ.diff(Tree.empty().id(), populated).added()).hasSize(1);
        }

        @Test
        void twoEmptyTreesDifferInNothing() {
            ObjectId empty = new TreeBuilder(store).build();

            assertThat(differ.diff(empty, empty).isEmpty()).isTrue();
        }

        @Test
        void changesAreSortedByPath() {
            ObjectId before = tree("z.txt", "z\n", "a.txt", "a\n", "m/deep.txt", "m\n");
            ObjectId after = tree("z.txt", "Z\n", "a.txt", "A\n", "m/deep.txt", "M\n");

            assertThat(differ.diff(before, after).changes())
                    .extracting(TreeChange::path)
                    .containsExactly("a.txt", "m/deep.txt", "z.txt")
                    .isSorted();
        }

        @Test
        void reversingTheArgumentsInvertsTheChanges() {
            ObjectId before = tree("stay.txt", "same\n", "gone.txt", "bye\n");
            ObjectId after = tree("stay.txt", "same\n", "fresh.txt", "hi\n");

            TreeDiff forward = differ.diff(before, after);
            TreeDiff backward = differ.diff(after, before);

            assertThat(forward.added()).extracting(TreeChange.Added::path).containsExactly("fresh.txt");
            assertThat(backward.deleted()).extracting(TreeChange.Deleted::path).containsExactly("fresh.txt");
            assertThat(forward.paths()).isEqualTo(backward.paths());
        }

        @Test
        void repeatedComparisonsAgree() {
            ObjectId before = tree("a.txt", "old\n");
            ObjectId after = tree("a.txt", "new\n");

            assertThat(differ.diff(before, after)).isEqualTo(differ.diff(before, after));
        }

        @Test
        void rejectsNullArguments() {
            ObjectId root = tree("a.txt", "a\n");

            assertThatThrownBy(() -> differ.diff(null, root)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> differ.diff(root, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TreeDiffer(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
