package com.gitforge.vcs.tree;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.storage.ObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Merkle properties the rest of the system will rely on.
 *
 * <p>Together these establish that a single root hash is a faithful summary of an
 * entire repository state: it changes whenever anything beneath it changes, it
 * does not change otherwise, and two states are identical exactly when their
 * roots match. Later phases compare whole commits by comparing one hash, and
 * descend only where subtree hashes differ.
 */
class MerkleTreeTest {

    @TempDir
    Path repositoryRoot;

    private ObjectStore store;
    private TreeWalker walker;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(repositoryRoot);
        walker = new TreeWalker(store);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A repository with two independent subtrees, so changes can be localised. */
    private ObjectId buildRepository(String appContent, String docsContent) {
        return new TreeBuilder(store)
                .addFile("README.md", bytes("# Project\n"))
                .addFile("src/App.java", bytes(appContent))
                .addFile("src/User.java", bytes("class User {}\n"))
                .addFile("docs/guide.md", bytes(docsContent))
                .build();
    }

    private ObjectId subtreeId(ObjectId root, String name) {
        return store.readTree(root).entry(name).map(TreeEntry::id).orElseThrow();
    }

    @Test
    @DisplayName("identical repository states produce identical root hashes")
    void identicalStatesShareARoot() {
        ObjectId first = buildRepository("class App {}\n", "# Guide\n");
        ObjectId second = buildRepository("class App {}\n", "# Guide\n");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("an unchanged repository keeps the same root hash")
    void unchangedRepositoryKeepsItsRoot() {
        ObjectId before = buildRepository("class App {}\n", "# Guide\n");

        // Rebuilding without editing anything must be a no-op for identity.
        ObjectId after = buildRepository("class App {}\n", "# Guide\n");

        assertThat(after).isEqualTo(before);
        assertThat(walker.flatten(after)).isEqualTo(walker.flatten(before));
    }

    @Test
    @DisplayName("changing one deep file changes the root hash")
    void changingADeepFileChangesTheRoot() {
        ObjectId before = buildRepository("class App {}\n", "# Guide\n");
        ObjectId after = buildRepository("class App { int x; }\n", "# Guide\n");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("a change propagates up every level of the tree")
    void changePropagatesUpTheSpine() {
        ObjectId before = buildRepository("class App {}\n", "# Guide\n");
        ObjectId after = buildRepository("class App { int x; }\n", "# Guide\n");

        // The edited file's own subtree changed...
        assertThat(subtreeId(after, "src")).isNotEqualTo(subtreeId(before, "src"));
        // ...and so did the root above it.
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("an unrelated subtree keeps its hash when a sibling changes")
    void unrelatedSubtreeIsUnaffected() {
        ObjectId before = buildRepository("class App {}\n", "# Guide\n");
        ObjectId after = buildRepository("class App { int x; }\n", "# Guide\n");

        // This is what makes comparison cheap: docs/ is provably untouched, so a
        // later diff can skip it entirely without reading a single file inside.
        assertThat(subtreeId(after, "docs")).isEqualTo(subtreeId(before, "docs"));
        assertThat(store.readTree(after).entry("README.md"))
                .isEqualTo(store.readTree(before).entry("README.md"));
    }

    @Test
    @DisplayName("comparing states by root hash localises the difference")
    void comparisonByRootHashLocalisesTheDifference() {
        ObjectId before = buildRepository("class App {}\n", "# Guide\n");
        ObjectId after = buildRepository("class App {}\n", "# Guide, revised\n");

        assertThat(after).isNotEqualTo(before);

        // Descend only where the hashes disagree: src/ is identical, docs/ is not.
        assertThat(subtreeId(after, "src")).isEqualTo(subtreeId(before, "src"));
        assertThat(subtreeId(after, "docs")).isNotEqualTo(subtreeId(before, "docs"));
    }

    @Test
    @DisplayName("reverting content restores the original root hash")
    void revertingRestoresTheOriginalRoot() {
        ObjectId original = buildRepository("class App {}\n", "# Guide\n");
        ObjectId edited = buildRepository("class App { int x; }\n", "# Guide\n");
        ObjectId reverted = buildRepository("class App {}\n", "# Guide\n");

        assertThat(edited).isNotEqualTo(original);
        // History is content-addressed, so returning to old content returns to
        // the old identity exactly.
        assertThat(reverted).isEqualTo(original);
    }

    @Test
    @DisplayName("adding a file changes the root")
    void addingAFileChangesTheRoot() {
        ObjectId before = buildRepository("class App {}\n", "# Guide\n");

        ObjectId after = new TreeBuilder(store)
                .addFile("README.md", bytes("# Project\n"))
                .addFile("src/App.java", bytes("class App {}\n"))
                .addFile("src/User.java", bytes("class User {}\n"))
                .addFile("src/Extra.java", bytes("class Extra {}\n"))
                .addFile("docs/guide.md", bytes("# Guide\n"))
                .build();

        assertThat(after).isNotEqualTo(before);
        assertThat(subtreeId(after, "docs")).isEqualTo(subtreeId(before, "docs"));
    }

    @Test
    @DisplayName("removing a file changes the root")
    void removingAFileChangesTheRoot() {
        ObjectId before = buildRepository("class App {}\n", "# Guide\n");

        ObjectId after = new TreeBuilder(store)
                .addFile("README.md", bytes("# Project\n"))
                .addFile("src/App.java", bytes("class App {}\n"))
                .addFile("docs/guide.md", bytes("# Guide\n"))
                .build();

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("moving a file changes the tree but reuses the blob")
    void movingAFileReusesItsBlob() {
        ObjectId before = new TreeBuilder(store).addFile("old/name.txt", bytes("unchanged content")).build();
        ObjectId blobBefore = walker.flatten(before).getFirst().id();
        long objectsBefore = store.count();

        ObjectId after = new TreeBuilder(store).addFile("new/name.txt", bytes("unchanged content")).build();
        ObjectId blobAfter = walker.flatten(after).getFirst().id();

        assertThat(after).isNotEqualTo(before);
        // Content is unchanged, so the blob is shared rather than duplicated.
        assertThat(blobAfter).isEqualTo(blobBefore);

        // Only the root is genuinely new. The subdirectory holding the file has
        // exactly the same listing as before — one entry, same name, same blob —
        // so it is the same tree object under a different name in the root.
        assertThat(store.count() - objectsBefore).isEqualTo(1);
        assertThat(subtreeId(after, "new")).isEqualTo(subtreeId(before, "old"));
    }

    @Test
    @DisplayName("identical directories are stored as a single shared tree")
    void duplicateContentIsStoredOnce() {
        ObjectId root = new TreeBuilder(store)
                .addFile("a/copy.txt", bytes("identical"))
                .addFile("b/copy.txt", bytes("identical"))
                .build();

        var files = walker.flatten(root);

        assertThat(files).hasSize(2);
        assertThat(files.get(0).id()).isEqualTo(files.get(1).id());

        // Deduplication is not limited to file contents. Directories a/ and b/
        // have byte-identical listings, so they hash to one id and are stored
        // once: 1 blob + 1 shared subtree + 1 root.
        assertThat(subtreeId(root, "a")).isEqualTo(subtreeId(root, "b"));
        assertThat(store.count()).isEqualTo(3);
    }
}
