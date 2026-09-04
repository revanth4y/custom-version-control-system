package com.gitforge.vcs.storage;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enumerating the store faster must not enumerate it differently.
 *
 * <p>{@code count} and {@code listIds} used to walk the object tree and then ask
 * the filesystem about every entry in turn. That second question was almost the
 * whole cost - on thirty thousand objects the walk took sixty milliseconds and
 * the questions another fourteen hundred - so the walk now takes the attributes
 * the directory scan already read.
 *
 * <p>Which means the answer has to be checked against the old way of arriving at
 * it, on stores that contain the awkward things a store can contain: temporary
 * files mid-write, an empty shard left by a sweep, a stray directory, a file
 * where no file should be. The reference below is the previous implementation,
 * written out, and every case requires the two to agree exactly - including
 * order, which {@code listIds} callers are entitled to rely on.
 */
class ObjectEnumerationTest {

    @TempDir
    Path root;

    private FileSystemObjectStore store;
    private Path objectsRoot;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(root);
        objectsRoot = root.resolve("objects");
    }

    private ObjectId write(String content) {
        return store.write(new Blob(content.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------- reference

    /** Counting as it was done before: walk everything, ask about each entry. */
    private long referenceCount() throws IOException {
        if (!Files.isDirectory(objectsRoot)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(objectsRoot)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(".tmp-"))
                    .count();
        }
    }

    /** Listing as it was done before, in the same order. */
    private List<ObjectId> referenceListIds() throws IOException {
        if (!Files.isDirectory(objectsRoot)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(objectsRoot)) {
            List<ObjectId> ids = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(".tmp-"))
                    .forEach(path -> ids.add(ObjectId.fromHex(
                            path.getParent().getFileName() + path.getFileName().toString())));
            return ids;
        }
    }

    private void assertAgrees() throws IOException {
        assertThat(store.count()).as("count").isEqualTo(referenceCount());
        assertThat(store.listIds())
                .as("listIds, in order")
                .containsExactlyElementsOf(referenceListIds());
    }

    // ---------------------------------------------------------------- shapes

    @Nested
    @DisplayName("the fast enumeration agrees with the old one")
    class Equivalence {

        @Test
        void onAnEmptyStore() throws IOException {
            assertAgrees();
            assertThat(store.count()).isZero();
        }

        @Test
        void onOneObject() throws IOException {
            write("only one");
            assertAgrees();
            assertThat(store.count()).isEqualTo(1);
        }

        @Test
        void onManyObjectsAcrossManyShards() throws IOException {
            for (int i = 0; i < 500; i++) {
                write("object " + i);
            }
            assertAgrees();
            assertThat(store.count()).isEqualTo(500);
        }

        @Test
        @DisplayName("a temporary file mid-write is not an object")
        void withATemporaryFile() throws IOException {
            ObjectId id = write("real");
            Path shard = objectsRoot.resolve(id.toHex().substring(0, 2));
            Files.writeString(shard.resolve(".tmp-halfwritten"), "not finished");

            assertAgrees();
            assertThat(store.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty shard left by a sweep counts nothing")
        void withAnEmptyShard() throws IOException {
            write("real");
            Files.createDirectories(objectsRoot.resolve("ff"));

            assertAgrees();
            assertThat(store.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a directory where a file should be is not an object")
        void withADirectoryAmongTheObjects() throws IOException {
            ObjectId id = write("real");
            Path shard = objectsRoot.resolve(id.toHex().substring(0, 2));
            Files.createDirectories(shard.resolve("00112233445566778899aabbccddeeff00112233"));

            assertAgrees();
            assertThat(store.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("what the store holds after deletions")
        void afterDeletions() throws IOException {
            List<ObjectId> ids = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                ids.add(write("object " + i));
            }
            for (int i = 0; i < 60; i += 2) {
                store.delete(ids.get(i));
            }

            assertAgrees();
            assertThat(store.count()).isEqualTo(30);
        }

        @Test
        @DisplayName("a count taken after a write includes the write")
        void afterAWrite() throws IOException {
            write("first");
            long before = store.count();
            write("second");

            assertThat(store.count())
                    .as("no index to fall behind: the answer comes from the store")
                    .isEqualTo(before + 1);
            assertAgrees();
        }

        @Test
        @DisplayName("a second store sees what the first one wrote")
        void acrossStoreInstances() throws IOException {
            write("written here");
            FileSystemObjectStore other = new FileSystemObjectStore(root);

            assertThat(other.count()).isEqualTo(store.count());
            assertThat(other.listIds()).containsExactlyElementsOf(store.listIds());

            other.write(new Blob("written there".getBytes(StandardCharsets.UTF_8)));
            assertThat(store.count())
                    .as("and the first sees what the second wrote, without being told")
                    .isEqualTo(2);
            assertAgrees();
        }
    }
}
