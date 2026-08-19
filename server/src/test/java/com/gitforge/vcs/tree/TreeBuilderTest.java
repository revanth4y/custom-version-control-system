package com.gitforge.vcs.tree;

import com.gitforge.vcs.GoldenVectors;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.storage.ObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreeBuilderTest {

    @TempDir
    Path repositoryRoot;

    private ObjectStore store;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(repositoryRoot);
    }

    private TreeBuilder builder() {
        return new TreeBuilder(store);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The worked example from the design:
     *
     * <pre>
     *   ROOT
     *   |-- README.md
     *   |-- pom.xml
     *   `-- src/
     *       |-- App.java
     *       `-- User.java
     * </pre>
     */
    private TreeBuilder demoRepository() {
        return builder()
                .addFile("README.md", bytes("# Demo\n"))
                .addFile("pom.xml", bytes("<project/>\n"))
                .addFile("src/App.java", bytes("class App {}\n"))
                .addFile("src/User.java", bytes("class User {}\n"));
    }

    @Nested
    @DisplayName("Merkle construction")
    class Construction {

        @Test
        void buildsNestedTreesAndReturnsTheGoldenRoot() {
            ObjectId root = demoRepository().build();

            assertThat(root.toHex()).isEqualTo(GoldenVectors.TREE_ROOT);
        }

        @Test
        void writesEveryIntermediateTreeToTheStore() {
            ObjectId root = demoRepository().build();

            // 4 blobs + src tree + root tree.
            assertThat(store.count()).isEqualTo(6);
            assertThat(store.contains(ObjectId.fromHex(GoldenVectors.TREE_SRC))).isTrue();
            assertThat(store.contains(ObjectId.fromHex(GoldenVectors.BLOB_APP_JAVA))).isTrue();
            assertThat(store.readTree(root).entries()).hasSize(3);
        }

        @Test
        void subdirectoryIsRecordedAsADirectoryEntry() {
            ObjectId root = demoRepository().build();

            assertThat(store.readTree(root).entry("src")).isPresent().get()
                    .satisfies(entry -> {
                        assertThat(entry.mode()).isEqualTo(FileMode.DIRECTORY);
                        assertThat(entry.id().toHex()).isEqualTo(GoldenVectors.TREE_SRC);
                    });
        }

        @Test
        void buildsDeeplyNestedPaths() {
            ObjectId root = builder().addFile("a/b/c/d/deep.txt", bytes("deep")).build();

            TreeWalker walker = new TreeWalker(store);
            assertThat(walker.flatten(root)).extracting(TreeWalker.Entry::path)
                    .containsExactly("a/b/c/d/deep.txt");

            // One tree per directory level, plus the root, plus the blob.
            assertThat(store.count()).isEqualTo(6);
        }

        @Test
        void anEmptyRepositoryBuildsTheEmptyTree() {
            assertThat(builder().build().toHex()).isEqualTo(GoldenVectors.EMPTY_TREE);
        }

        @Test
        void preservesExecutableMode() {
            ObjectId root = builder()
                    .addFile("run.sh", bytes("A\n"), FileMode.EXECUTABLE_FILE)
                    .build();

            assertThat(root.toHex()).isEqualTo(GoldenVectors.TREE_EXECUTABLE);
            assertThat(store.readTree(root).entry("run.sh")).get()
                    .extracting(TreeEntry::mode).isEqualTo(FileMode.EXECUTABLE_FILE);
        }

        @Test
        void acceptsAPreviouslyStoredBlobId() {
            ObjectId blobId = store.write(new com.gitforge.vcs.object.Blob(bytes("A\n")));

            ObjectId root = builder().add("run.sh", FileMode.EXECUTABLE_FILE, blobId).build();

            assertThat(root.toHex()).isEqualTo(GoldenVectors.TREE_EXECUTABLE);
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void insertionOrderDoesNotAffectTheRootHash() {
            ObjectId forwards = demoRepository().build();

            ObjectId backwards = builder()
                    .addFile("src/User.java", bytes("class User {}\n"))
                    .addFile("src/App.java", bytes("class App {}\n"))
                    .addFile("pom.xml", bytes("<project/>\n"))
                    .addFile("README.md", bytes("# Demo\n"))
                    .build();

            assertThat(forwards).isEqualTo(backwards);
        }

        @Test
        void rebuildingTheSameContentYieldsTheSameRoot() {
            assertThat(demoRepository().build()).isEqualTo(demoRepository().build());
        }

        @Test
        void identicalContentInSeparateStoresYieldsTheSameRoot() {
            // Identity depends only on content, never on which store holds it.
            ObjectStore otherStore = new FileSystemObjectStore(repositoryRoot.resolve("other"));

            ObjectId here = demoRepository().build();
            ObjectId there = new TreeBuilder(otherStore)
                    .addFile("README.md", bytes("# Demo\n"))
                    .addFile("pom.xml", bytes("<project/>\n"))
                    .addFile("src/App.java", bytes("class App {}\n"))
                    .addFile("src/User.java", bytes("class User {}\n"))
                    .build();

            assertThat(here).isEqualTo(there);
        }

        @Test
        void rebuildingUnchangedContentStoresNoNewObjects() {
            demoRepository().build();
            long afterFirst = store.count();

            demoRepository().build();

            assertThat(store.count()).isEqualTo(afterFirst);
        }
    }

    @Nested
    @DisplayName("path validation")
    class PathValidation {

        @Test
        void rejectsParentDirectoryTraversal() {
            // Without this a crafted tree could write outside the repository.
            assertThatThrownBy(() -> builder().addFile("../escape.txt", bytes("x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'.' or '..'");

            assertThatThrownBy(() -> builder().addFile("src/../../escape.txt", bytes("x")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAbsolutePaths() {
            assertThatThrownBy(() -> builder().addFile("/etc/passwd", bytes("x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("relative");
        }

        @Test
        void rejectsEmptySegments() {
            assertThatThrownBy(() -> builder().addFile("src//App.java", bytes("x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty segments");
        }

        @Test
        void rejectsBlankPaths() {
            assertThatThrownBy(() -> builder().addFile("", bytes("x")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder().addFile("   ", bytes("x")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void normalisesWindowsSeparators() {
            ObjectId root = builder().addFile("src\\App.java", bytes("class App {}\n")).build();

            assertThat(new TreeWalker(store).flatten(root)).extracting(TreeWalker.Entry::path)
                    .containsExactly("src/App.java");
        }

        @Test
        void rejectsAPathThatIsBothAFileAndADirectory() {
            assertThatThrownBy(() -> builder()
                    .addFile("src", bytes("a file"))
                    .addFile("src/App.java", bytes("inside a directory"))
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already used by a file");
        }

        @Test
        void rejectsADirectoryLaterUsedAsAFile() {
            // Conflicts surface at build(), when the flat paths are assembled
            // into a nested structure; add() only records a path.
            assertThatThrownBy(() -> builder()
                    .addFile("src/App.java", bytes("inside a directory"))
                    .addFile("src", bytes("a file"))
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already used by a directory");
        }

        @Test
        void rejectsADirectoryMode() {
            assertThatThrownBy(() -> builder().add("src", FileMode.DIRECTORY, ObjectId.fromHex(GoldenVectors.TREE_SRC)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-directory mode");
        }

        @Test
        void lastWriteWinsForARepeatedPath() {
            ObjectId root = builder()
                    .addFile("file.txt", bytes("first"))
                    .addFile("file.txt", bytes("second"))
                    .build();

            ObjectId blobId = new TreeWalker(store).flatten(root).getFirst().id();
            assertThat(store.readBlob(blobId).payload()).isEqualTo(bytes("second"));
        }
    }
}
