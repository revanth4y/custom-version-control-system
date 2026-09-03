package com.gitforge.vcs.object;

import com.gitforge.vcs.GoldenVectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TreeTest {

    private static TreeEntry file(String name, String hex) {
        return new TreeEntry(FileMode.REGULAR_FILE, name, ObjectId.fromHex(hex));
    }

    private static TreeEntry directory(String name, String hex) {
        return new TreeEntry(FileMode.DIRECTORY, name, ObjectId.fromHex(hex));
    }

    @Nested
    @DisplayName("identity matches an independent implementation")
    class GoldenIdentity {

        @Test
        void subtreeOfTwoFiles() {
            Tree src = new Tree(List.of(
                    file("App.java", GoldenVectors.BLOB_APP_JAVA),
                    file("User.java", GoldenVectors.BLOB_USER_JAVA)));

            assertThat(src.id().toHex()).isEqualTo(GoldenVectors.TREE_SRC);
        }

        @Test
        void rootTreeContainingASubtree() {
            Tree root = new Tree(List.of(
                    file("README.md", GoldenVectors.BLOB_README),
                    file("pom.xml", GoldenVectors.BLOB_POM),
                    directory("src", GoldenVectors.TREE_SRC)));

            assertThat(root.id().toHex()).isEqualTo(GoldenVectors.TREE_ROOT);
        }

        @Test
        void emptyTreeHasTheWellKnownId() {
            assertThat(Tree.empty().id().toHex()).isEqualTo(GoldenVectors.EMPTY_TREE);
            assertThat(Tree.empty().isEmpty()).isTrue();
        }

        @Test
        void executableModeIsPartOfIdentity() {
            Tree executable = new Tree(List.of(
                    new TreeEntry(FileMode.EXECUTABLE_FILE, "run.sh",
                            ObjectId.fromHex(GoldenVectors.BLOB_A_NEWLINE))));

            assertThat(executable.id().toHex()).isEqualTo(GoldenVectors.TREE_EXECUTABLE);
        }

        @Test
        void changingOnlyTheModeChangesTheTreeId() {
            ObjectId content = ObjectId.fromHex(GoldenVectors.BLOB_A_NEWLINE);

            Tree asRegular = new Tree(List.of(new TreeEntry(FileMode.REGULAR_FILE, "run.sh", content)));
            Tree asExecutable = new Tree(List.of(new TreeEntry(FileMode.EXECUTABLE_FILE, "run.sh", content)));

            assertThat(asRegular.id()).isNotEqualTo(asExecutable.id());
        }
    }

    @Nested
    @DisplayName("canonical ordering")
    class Ordering {

        @Test
        void insertionOrderDoesNotAffectTheId() {
            List<TreeEntry> entries = List.of(
                    file("README.md", GoldenVectors.BLOB_README),
                    file("pom.xml", GoldenVectors.BLOB_POM),
                    directory("src", GoldenVectors.TREE_SRC));

            Tree forwards = new Tree(entries);
            Tree backwards = new Tree(Arrays.asList(
                    entries.get(2), entries.get(1), entries.get(0)));

            assertThat(forwards.id()).isEqualTo(backwards.id());
            assertThat(forwards.id().toHex()).isEqualTo(GoldenVectors.TREE_ROOT);
        }

        @Test
        void entriesAreExposedInCanonicalOrderNotInsertionOrder() {
            Tree tree = new Tree(List.of(
                    directory("src", GoldenVectors.TREE_SRC),
                    file("pom.xml", GoldenVectors.BLOB_POM),
                    file("README.md", GoldenVectors.BLOB_README)));

            assertThat(tree.entries()).extracting(TreeEntry::name)
                    .containsExactly("README.md", "pom.xml", "src");
        }

        @Test
        void aFileSortsBeforeASimilarlyNamedDirectoryWhenTheNextByteIsBelowSlash() {
            // "src.txt" vs "src/" -> '.' (0x2E) is below '/' (0x2F), so the file wins.
            Tree tree = new Tree(List.of(
                    directory("src", GoldenVectors.TREE_SUB),
                    file("src.txt", GoldenVectors.BLOB_B_NEWLINE)));

            assertThat(tree.entries()).extracting(TreeEntry::name).containsExactly("src.txt", "src");
            assertThat(tree.id().toHex()).isEqualTo(GoldenVectors.TREE_SRC_AND_SRC_TXT);
        }

        @Test
        void aDirectorySortsBeforeASimilarlyNamedFileWhenTheNextByteIsAboveSlash() {
            // "src/" vs "src0" -> '/' (0x2F) is below '0' (0x30), so the directory wins.
            Tree tree = new Tree(List.of(
                    file("src0", GoldenVectors.BLOB_B_NEWLINE),
                    directory("src", GoldenVectors.TREE_SUB)));

            assertThat(tree.entries()).extracting(TreeEntry::name).containsExactly("src", "src0");
            assertThat(tree.id().toHex()).isEqualTo(GoldenVectors.TREE_SRC_AND_SRC0);
        }

        @Test
        void namingIsCaseSensitive() {
            // Uppercase letters sort below lowercase in byte order.
            Tree tree = new Tree(List.of(
                    file("b.txt", GoldenVectors.BLOB_B),
                    file("A.txt", GoldenVectors.BLOB_A)));

            assertThat(tree.entries()).extracting(TreeEntry::name).containsExactly("A.txt", "b.txt");
        }
    }

    @Nested
    @DisplayName("serialization")
    class Serialization {

        @Test
        void entryUsesModeSpaceNameNulThenTwentyRawBytes() {
            Tree tree = new Tree(List.of(file("App.java", GoldenVectors.BLOB_APP_JAVA)));
            byte[] payload = tree.payload();

            byte[] prefix = "100644 App.java".getBytes(StandardCharsets.US_ASCII);
            assertThat(Arrays.copyOfRange(payload, 0, prefix.length)).isEqualTo(prefix);

            // The NUL terminator, then the id as raw bytes rather than hex text.
            assertThat(payload[prefix.length]).isZero();
            assertThat(Arrays.copyOfRange(payload, prefix.length + 1, payload.length))
                    .isEqualTo(ObjectId.fromHex(GoldenVectors.BLOB_APP_JAVA).toBytes());

            assertThat(payload).hasSize(prefix.length + 1 + ObjectId.LENGTH);
        }

        @Test
        void directoryModeIsWrittenWithoutALeadingZero() {
            Tree tree = new Tree(List.of(directory("src", GoldenVectors.TREE_SRC)));

            assertThat(new String(tree.payload(), 0, 9, StandardCharsets.US_ASCII)).isEqualTo("40000 src");
        }

        @Test
        void entriesAreConcatenatedWithoutSeparators() {
            Tree tree = new Tree(List.of(
                    file("a", GoldenVectors.BLOB_A),
                    file("b", GoldenVectors.BLOB_B)));

            // Two entries of "100644 x" + NUL + 20 bytes.
            assertThat(tree.payload()).hasSize(2 * ("100644 a".length() + 1 + ObjectId.LENGTH));
        }

        @Test
        void payloadRoundTripsThroughParse() {
            Tree original = new Tree(List.of(
                    file("README.md", GoldenVectors.BLOB_README),
                    directory("src", GoldenVectors.TREE_SRC)));

            VcsObject parsed = ObjectFormat.parse(ObjectFormat.serialize(original));

            assertThat(parsed).isInstanceOf(Tree.class);
            assertThat(((Tree) parsed).entries()).isEqualTo(original.entries());
            assertThat(parsed.id()).isEqualTo(original.id());
        }

        @Test
        void emptyTreeSerializesToAnEmptyPayload() {
            assertThat(Tree.empty().payload()).isEmpty();
            assertThat(new String(ObjectFormat.serialize(Tree.empty()), StandardCharsets.US_ASCII))
                    .isEqualTo("tree 0\0");
        }

        @Test
        void unicodeNamesAreEncodedAsUtf8() {
            Tree tree = new Tree(List.of(file("résumé.txt", GoldenVectors.BLOB_A)));
            byte[] payload = tree.payload();

            byte[] expectedName = "résumé.txt".getBytes(StandardCharsets.UTF_8);
            assertThat(Arrays.copyOfRange(payload, 7, 7 + expectedName.length)).isEqualTo(expectedName);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void rejectsDuplicateNames() {
            assertThatThrownBy(() -> new Tree(List.of(
                    file("same", GoldenVectors.BLOB_A),
                    file("same", GoldenVectors.BLOB_B))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate entry name");
        }

        @Test
        void rejectsNamesContainingASeparator() {
            assertThatThrownBy(() -> file("src/App.java", GoldenVectors.BLOB_A))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("single path component");
        }

        @Test
        void rejectsRelativePathSegments() {
            assertThatThrownBy(() -> file("..", GoldenVectors.BLOB_A))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> file(".", GoldenVectors.BLOB_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsEmptyNames() {
            assertThatThrownBy(() -> file("", GoldenVectors.BLOB_A))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsNamesContainingNul() {
            assertThatThrownBy(() -> file("bad\0name", GoldenVectors.BLOB_A))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NUL");
        }
    }

    @Test
    void looksUpEntriesByName() {
        Tree tree = new Tree(List.of(
                file("README.md", GoldenVectors.BLOB_README),
                directory("src", GoldenVectors.TREE_SRC)));

        assertThat(tree.entry("src")).isPresent().get()
                .extracting(TreeEntry::isDirectory).isEqualTo(true);
        assertThat(tree.entry("missing")).isEmpty();
    }
}
