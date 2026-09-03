package com.gitforge.vcs.tree;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.storage.ObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreeWalkerTest {

    @TempDir
    Path repositoryRoot;

    private ObjectStore store;
    private TreeWalker walker;
    private ObjectId root;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(repositoryRoot);
        walker = new TreeWalker(store);
        root = new TreeBuilder(store)
                .addFile("README.md", bytes("# Demo\n"))
                .addFile("pom.xml", bytes("<project/>\n"))
                .addFile("src/App.java", bytes("class App {}\n"))
                .addFile("src/main/Deep.java", bytes("class Deep {}\n"))
                .build();
    }

    @Test
    void flattensToFullPathsInCanonicalOrder() {
        assertThat(walker.flatten(root)).extracting(TreeWalker.Entry::path)
                .containsExactly("README.md", "pom.xml", "src/App.java", "src/main/Deep.java");
    }

    @Test
    void flattenReportsFilesOnlyNotDirectories() {
        assertThat(walker.flatten(root)).extracting(TreeWalker.Entry::mode)
                .allMatch(mode -> mode != FileMode.DIRECTORY);
    }

    @Test
    void flattenCarriesTheBlobIdForEachFile() {
        TreeWalker.Entry readme = walker.flatten(root).stream()
                .filter(entry -> entry.path().equals("README.md"))
                .findFirst().orElseThrow();

        assertThat(store.readBlob(readme.id()).payload()).isEqualTo(bytes("# Demo\n"));
    }

    @Test
    void flattenOfTheEmptyTreeIsEmpty() {
        ObjectId empty = new TreeBuilder(store).build();

        assertThat(walker.flatten(empty)).isEmpty();
    }

    @Test
    void listsImmediateChildrenWithoutDescending() {
        assertThat(walker.list(root)).extracting(TreeEntry::name)
                .containsExactly("README.md", "pom.xml", "src");
    }

    @Test
    void resolvesAFileAtTheRoot() {
        assertThat(walker.resolve(root, "README.md")).isPresent().get()
                .extracting(TreeEntry::isDirectory).isEqualTo(false);
    }

    @Test
    void resolvesANestedFile() {
        assertThat(walker.resolve(root, "src/main/Deep.java")).isPresent().get()
                .satisfies(entry -> assertThat(store.readBlob(entry.id()).payload())
                        .isEqualTo(bytes("class Deep {}\n")));
    }

    @Test
    void resolvesADirectory() {
        assertThat(walker.resolve(root, "src")).isPresent().get()
                .extracting(TreeEntry::isDirectory).isEqualTo(true);
    }

    @Test
    void returnsEmptyForAMissingPath() {
        assertThat(walker.resolve(root, "nope.txt")).isEmpty();
        assertThat(walker.resolve(root, "src/nope.txt")).isEmpty();
        assertThat(walker.resolve(root, "nope/deeper.txt")).isEmpty();
    }

    @Test
    void returnsEmptyWhenAPathContinuesPastAFile() {
        assertThat(walker.resolve(root, "README.md/impossible")).isEmpty();
    }

    @Test
    void acceptsWindowsSeparators() {
        assertThat(walker.resolve(root, "src\\App.java")).isPresent();
    }

    @Test
    void rejectsABlankPath() {
        assertThatThrownBy(() -> walker.resolve(root, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> walker.resolve(root, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullStore() {
        assertThatThrownBy(() -> new TreeWalker(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
