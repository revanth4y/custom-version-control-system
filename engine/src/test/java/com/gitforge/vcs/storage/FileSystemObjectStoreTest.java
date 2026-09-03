package com.gitforge.vcs.storage;

import com.gitforge.vcs.GoldenVectors;
import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectFormat;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.Tree;
import com.gitforge.vcs.object.TreeEntry;
import com.gitforge.vcs.object.VcsObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemObjectStoreTest {

    @TempDir
    Path repositoryRoot;

    private FileSystemObjectStore store;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(repositoryRoot);
    }

    private static Blob blob(String content) {
        return new Blob(content.getBytes(StandardCharsets.UTF_8));
    }

    private Path fileFor(ObjectId id) {
        String hex = id.toHex();
        return repositoryRoot.resolve("objects").resolve(hex.substring(0, 2)).resolve(hex.substring(2));
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void blobSurvivesWriteAndRead() {
            Blob original = blob("hello world");
            ObjectId id = store.write(original);

            assertThat(id.toHex()).isEqualTo(GoldenVectors.BLOB_HELLO_WORLD);
            assertThat(store.read(id)).isPresent().get()
                    .satisfies(read -> assertThat(read.payload()).isEqualTo(original.payload()));
        }

        @Test
        void binaryContentSurvivesCompressionUnaltered() {
            byte[] binary = new byte[256];
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) i;
            }
            ObjectId id = store.write(new Blob(binary));

            assertThat(store.readBlob(id).payload()).isEqualTo(binary);
        }

        @Test
        void emptyBlobSurvives() {
            ObjectId id = store.write(new Blob(new byte[0]));

            assertThat(id.toHex()).isEqualTo(GoldenVectors.EMPTY_BLOB);
            assertThat(store.readBlob(id).payload()).isEmpty();
        }

        @Test
        void treeSurvivesWriteAndRead() {
            Tree tree = new Tree(List.of(
                    new TreeEntry(FileMode.REGULAR_FILE, "App.java", ObjectId.fromHex(GoldenVectors.BLOB_APP_JAVA)),
                    new TreeEntry(FileMode.REGULAR_FILE, "User.java", ObjectId.fromHex(GoldenVectors.BLOB_USER_JAVA))));

            ObjectId id = store.write(tree);

            assertThat(id.toHex()).isEqualTo(GoldenVectors.TREE_SRC);
            assertThat(store.readTree(id).entries()).isEqualTo(tree.entries());
        }

        @Test
        void largeContentSurvives() {
            byte[] large = new byte[512 * 1024];
            for (int i = 0; i < large.length; i++) {
                large[i] = (byte) (i * 31);
            }
            ObjectId id = store.write(new Blob(large));

            assertThat(store.readBlob(id).payload()).isEqualTo(large);
        }

        @Test
        void readingAnAbsentObjectReturnsEmpty() {
            assertThat(store.read(ObjectId.fromHex("00".repeat(20)))).isEmpty();
        }
    }

    @Nested
    @DisplayName("content addressing")
    class ContentAddressing {

        @Test
        void objectsAreShardedByTheFirstTwoHexCharacters() {
            ObjectId id = store.write(blob("hello world"));

            assertThat(fileFor(id)).exists();
            assertThat(fileFor(id).getParent().getFileName()).hasToString(id.toHex().substring(0, 2));
            assertThat(fileFor(id).getFileName()).hasToString(id.toHex().substring(2));
        }

        @Test
        void identicalContentWrittenTwiceIsStoredOnce() {
            ObjectId first = store.write(blob("duplicated content"));
            ObjectId second = store.write(blob("duplicated content"));

            assertThat(first).isEqualTo(second);
            assertThat(store.count()).isEqualTo(1);
        }

        @Test
        void rewritingDoesNotDisturbTheStoredFile() throws IOException {
            ObjectId id = store.write(blob("stable"));
            byte[] before = Files.readAllBytes(fileFor(id));

            store.write(blob("stable"));

            assertThat(Files.readAllBytes(fileFor(id))).isEqualTo(before);
            assertThat(store.count()).isEqualTo(1);
        }

        @Test
        void identicalContentAtDifferentPathsSharesOneObject() {
            // Blobs carry no name, so the same bytes are one object however
            // many places reference them.
            Blob shared = blob("shared content");
            store.write(shared);
            store.write(new Blob("shared content".getBytes(StandardCharsets.UTF_8)));

            assertThat(store.count()).isEqualTo(1);
        }

        @Test
        void differentContentProducesSeparateObjects() {
            store.write(blob("one"));
            store.write(blob("two"));

            assertThat(store.count()).isEqualTo(2);
        }

        @Test
        void reportsWhetherAnObjectIsPresent() {
            ObjectId present = store.write(blob("present"));

            assertThat(store.contains(present)).isTrue();
            assertThat(store.contains(ObjectId.fromHex("00".repeat(20)))).isFalse();
        }

        @Test
        void countIsZeroForAFreshStore() {
            assertThat(new FileSystemObjectStore(repositoryRoot.resolve("fresh")).count()).isZero();
        }
    }

    @Nested
    @DisplayName("integrity")
    class Integrity {

        @Test
        void detectsAFlippedByteInTheStoredFile() throws IOException {
            ObjectId id = store.write(blob("content that will be corrupted"));

            byte[] stored = Files.readAllBytes(fileFor(id));
            stored[stored.length / 2] ^= (byte) 0xFF;
            Files.write(fileFor(id), stored);

            assertThatThrownBy(() -> store.read(id)).isInstanceOf(CorruptObjectException.class);
        }

        @Test
        void detectsATruncatedFile() throws IOException {
            ObjectId id = store.write(blob("content that will be truncated"));

            byte[] stored = Files.readAllBytes(fileFor(id));
            Files.write(fileFor(id), java.util.Arrays.copyOf(stored, stored.length / 2));

            assertThatThrownBy(() -> store.read(id))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("could not be decompressed");
        }

        @Test
        void detectsAnEmptyFile() throws IOException {
            ObjectId id = store.write(blob("content"));
            Files.write(fileFor(id), new byte[0]);

            assertThatThrownBy(() -> store.read(id)).isInstanceOf(CorruptObjectException.class);
        }

        @Test
        void detectsValidContentFiledUnderTheWrongId() throws IOException {
            // The most dangerous case: the bytes decompress and parse cleanly,
            // but are not the object that was asked for. Only the hash check
            // catches this.
            ObjectId requested = store.write(blob("the original"));
            byte[] impostor = compress(ObjectFormat.serialize(blob("a different object")));
            Files.write(fileFor(requested), impostor);

            assertThatThrownBy(() -> store.read(requested))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("actually hashes to");
        }

        @Test
        void detectsGarbageThatIsNotCompressedAtAll() throws IOException {
            ObjectId id = store.write(blob("content"));
            Files.write(fileFor(id), "not zlib data at all".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> store.read(id)).isInstanceOf(CorruptObjectException.class);
        }

        @Test
        void verifyPassesForAnIntactObject() {
            ObjectId id = store.write(blob("intact"));

            store.verify(id);
        }

        @Test
        void verifyReportsAMissingObject() {
            assertThatThrownBy(() -> store.verify(ObjectId.fromHex("00".repeat(20))))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("missing");
        }

        @Test
        void typedReadsRejectAMismatchedType() {
            ObjectId blobId = store.write(blob("actually a blob"));

            assertThatThrownBy(() -> store.readTree(blobId))
                    .isInstanceOf(CorruptObjectException.class)
                    .hasMessageContaining("not a tree");
        }
    }

    @Nested
    @DisplayName("storage layer")
    class StorageLayer {

        @Test
        void filesOnDiskAreCompressedNotPlaintext() throws IOException {
            // Highly compressible content proves the bytes are not stored raw.
            byte[] repetitive = "A".repeat(10_000).getBytes(StandardCharsets.UTF_8);
            ObjectId id = store.write(new Blob(repetitive));

            long onDisk = Files.size(fileFor(id));

            assertThat(onDisk).isLessThan(1_000);
            assertThat(store.readBlob(id).payload()).isEqualTo(repetitive);
        }

        @Test
        void compressionDoesNotAffectObjectIdentity() {
            // The id must come from the uncompressed representation.
            byte[] content = "A".repeat(10_000).getBytes(StandardCharsets.UTF_8);
            ObjectId expected = ObjectFormat.computeId(ObjectType.BLOB, content);

            assertThat(store.write(new Blob(content))).isEqualTo(expected);
        }

        @Test
        void leavesNoTemporaryFilesBehind() throws IOException {
            store.write(blob("one"));
            store.write(blob("two"));

            try (var paths = Files.walk(repositoryRoot)) {
                assertThat(paths.filter(Files::isRegularFile))
                        .allSatisfy(path -> assertThat(path.getFileName().toString()).doesNotStartWith(".tmp-"));
            }
        }

        @Test
        void createsTheObjectDirectoryOnConstruction() {
            new FileSystemObjectStore(repositoryRoot.resolve("nested").resolve("deeper"));

            assertThat(repositoryRoot.resolve("nested").resolve("deeper").resolve("objects")).isDirectory();
        }

        @Test
        void separateRepositoriesDoNotShareObjects() {
            FileSystemObjectStore other = new FileSystemObjectStore(repositoryRoot.resolve("other"));
            ObjectId id = store.write(blob("only in the first store"));

            assertThat(store.contains(id)).isTrue();
            assertThat(other.contains(id)).isFalse();
        }

        @Test
        void reopeningAStoreSeesPreviouslyWrittenObjects() {
            ObjectId id = store.write(blob("persisted"));

            FileSystemObjectStore reopened = new FileSystemObjectStore(repositoryRoot);

            assertThat(reopened.contains(id)).isTrue();
            assertThat(reopened.readBlob(id).payload()).isEqualTo(blob("persisted").payload());
        }

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> new FileSystemObjectStore(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.write((VcsObject) null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.read(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out)) {
            stream.write(data);
        }
        return out.toByteArray();
    }
}
