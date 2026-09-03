package com.gitforge.vcs.storage;

import com.gitforge.vcs.hash.Sha1;
import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.ObjectFormat;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.tree.TreeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.InflaterInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invariant the whole store rests on, asserted over every object it holds:
 *
 * <pre>
 *   ObjectId == SHA-1(canonical uncompressed object bytes)
 * </pre>
 *
 * <p>and therefore the path an object is filed under is itself the SHA-1 of that
 * object's canonical representation.
 *
 * <p>The other store tests check operations one at a time. This one checks the
 * property globally, reading the raw files back off disk and re-deriving
 * everything from scratch — no reliance on the store's own accessors for the
 * value being verified.
 */
class ObjectStoreInvariantTest {

    @TempDir
    Path repositoryRoot;

    private FileSystemObjectStore store;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(repositoryRoot);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A repository exercising blobs, nested trees, shared subtrees and binary content. */
    private void populateRepository() {
        new TreeBuilder(store)
                .addFile("README.md", bytes("# Demo\n"))
                .addFile("pom.xml", bytes("<project/>\n"))
                .addFile("src/App.java", bytes("class App {}\n"))
                .addFile("src/User.java", bytes("class User {}\n"))
                .addFile("src/main/Deep.java", bytes("class Deep {}\n"))
                .addFile("docs/guide.md", bytes("# Guide\n"))
                .build();

        store.write(new Blob(new byte[0]));
        store.write(new Blob(new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0xFE}));
        store.write(new Blob("A".repeat(5_000).getBytes(StandardCharsets.UTF_8)));
    }

    /** Reads a stored file and undoes only the compression, yielding canonical bytes. */
    private static byte[] canonicalBytesOnDisk(Path file) throws IOException {
        try (InputStream in = new InflaterInputStream(Files.newInputStream(file))) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("every stored object's id is the SHA-1 of its canonical uncompressed bytes")
    void everyStoredObjectHashesToItsId() throws IOException {
        populateRepository();
        List<ObjectId> ids = store.listIds();

        assertThat(ids).isNotEmpty();

        for (ObjectId id : ids) {
            byte[] canonical = canonicalBytesOnDisk(store.pathFor(id));

            // Hashed here directly, rather than through any store or object API,
            // so the assertion cannot be satisfied by the code it is checking.
            byte[] digest = Sha1.hash(canonical);

            assertThat(HexFormat.of().formatHex(digest))
                    .as("object %s must hash to its own id", id)
                    .isEqualTo(id.toHex());
        }
    }

    @Test
    @DisplayName("every object's file path is the SHA-1 of its canonical bytes")
    void everyObjectPathMatchesItsDigest() throws IOException {
        populateRepository();

        try (var paths = Files.walk(repositoryRoot.resolve("objects"))) {
            List<Path> files = paths.filter(Files::isRegularFile).toList();
            assertThat(files).isNotEmpty();

            for (Path file : files) {
                // Rebuild the id purely from where the file sits on disk.
                String shard = file.getParent().getFileName().toString();
                String remainder = file.getFileName().toString();

                assertThat(shard).hasSize(2);
                assertThat(remainder).hasSize(38);

                String digest = HexFormat.of().formatHex(Sha1.hash(canonicalBytesOnDisk(file)));

                assertThat(shard + remainder)
                        .as("path of %s must be the digest of its contents", file)
                        .isEqualTo(digest);
            }
        }
    }

    @Test
    @DisplayName("re-serializing an object read back reproduces the bytes on disk")
    void readingAndReserializingReproducesTheStoredBytes() throws IOException {
        populateRepository();

        for (ObjectId id : store.listIds()) {
            VcsObject object = store.read(id).orElseThrow();

            // Canonical form is stable across a full parse/serialize cycle...
            assertThat(ObjectFormat.serialize(object)).isEqualTo(canonicalBytesOnDisk(store.pathFor(id)));
            // ...and the object recomputes the same identity from its own contents.
            assertThat(object.id()).isEqualTo(id);
        }
    }

    @Test
    @DisplayName("the invariant holds for every object type the store can hold")
    void invariantCoversBlobsAndTrees() throws IOException {
        populateRepository();

        List<VcsObject> objects = store.listIds().stream()
                .map(id -> store.read(id).orElseThrow())
                .toList();

        // Guard against the population above silently degenerating to one type.
        assertThat(objects).extracting(object -> object.type().header())
                .contains("blob", "tree");

        for (VcsObject object : objects) {
            assertThat(ObjectId.ofContent(ObjectFormat.serialize(object))).isEqualTo(object.id());
        }
    }

    @Test
    void listIdsAgreesWithCount() {
        populateRepository();

        assertThat(store.listIds()).hasSize((int) store.count()).doesNotHaveDuplicates();
    }

    @Test
    void listIdsIsEmptyForAFreshStore() {
        assertThat(store.listIds()).isEmpty();
    }
}
