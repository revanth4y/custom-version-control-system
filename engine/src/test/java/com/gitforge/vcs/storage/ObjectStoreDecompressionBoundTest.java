package com.gitforge.vcs.storage;

import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An object file that is small on disk and enormous once inflated.
 *
 * <p>Reading an object used to end in {@code readAllBytes} with no ceiling on
 * it. A deflate stream's compressed length says nothing about its inflated one —
 * a few kilobytes of repeated bytes expand to gigabytes — so a single tampered
 * or damaged file under the storage root turned a read into an out-of-memory
 * error and took the process down with it. Every other kind of damage here is
 * reported as a corrupt object and handled; this one was not damage the code
 * survived to describe.
 *
 * <p><strong>What this is and is not.</strong> Nothing reachable through the API
 * can store a file like this: every write deflates content the server has
 * already bounded, so the compressed bytes on disk are its own work. The
 * attacker here is the one the threat model calls malicious filesystem state — a
 * shared volume, a restored backup, a corrupted sector that happens to inflate
 * badly. That is a narrower attacker than an anonymous caller, and the fix is
 * correspondingly modest: it converts a crash into a refusal. It is worth doing
 * because "the process dies" is a worse answer to a bad file than "that is a bad
 * file", and because the ceiling costs nothing to keep.
 *
 * <p>The bomb below inflates to a hundred megabytes rather than to gigabytes —
 * comfortably past the sixty-four megabyte limit and small enough that a failing
 * test fails in seconds instead of exhausting the heap of whatever runs it.
 */
class ObjectStoreDecompressionBoundTest {

    @TempDir
    Path root;

    /** Deflates {@code count} zero bytes, which is what makes a bomb a bomb. */
    private static byte[] bomb(int count) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            byte[] chunk = new byte[64 * 1024];
            for (int written = 0; written < count; written += chunk.length) {
                stream.write(chunk, 0, Math.min(chunk.length, count - written));
            }
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater();
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            stream.write(data);
        } finally {
            deflater.end();
        }
        return out.toByteArray();
    }

    /** Puts bytes on disk where the store will look for {@code id}, bypassing write(). */
    private void plant(FileSystemObjectStore store, ObjectId id, byte[] compressed)
            throws IOException {
        Path path = store.pathFor(id);
        Files.createDirectories(path.getParent());
        Files.write(path, compressed);
    }

    @Test
    @DisplayName("is refused as corrupt rather than read into memory")
    void bombIsRefused() throws IOException {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        // An id that will never match. What is being tested is that the read stops
        // before it ever gets as far as comparing hashes.
        ObjectId id = ObjectId.ofContent("anything".getBytes(StandardCharsets.UTF_8));

        plant(store, id, bomb(100 * 1024 * 1024));

        assertThatThrownBy(() -> store.read(id))
                .isInstanceOf(CorruptObjectException.class)
                .hasMessageContaining("decompresses to more than");
    }

    @Test
    @DisplayName("and the file that caused it is left where it was")
    void nothingIsDeleted() throws IOException {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        ObjectId id = ObjectId.ofContent("anything".getBytes(StandardCharsets.UTF_8));
        plant(store, id, bomb(100 * 1024 * 1024));

        assertThatThrownBy(() -> store.read(id)).isInstanceOf(CorruptObjectException.class);

        // Refusing to read something is not a reason to destroy it. Whoever
        // investigates needs the file that caused the refusal.
        assertThat(Files.exists(store.pathFor(id))).isTrue();
    }

    @Test
    @DisplayName("an object of ordinary size still reads exactly as before")
    void ordinaryObjectsAreUnaffected() throws IOException {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        byte[] content = "hello, world".getBytes(StandardCharsets.UTF_8);

        ObjectId id = store.write(new com.gitforge.vcs.object.Blob(content));

        assertThat(store.readBlob(id).payload()).isEqualTo(content);
    }

    @Test
    @DisplayName("damaged compressed data is still reported as corrupt, not as a bomb")
    void truncatedDataStillReportsCorruption() throws IOException {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        ObjectId id = ObjectId.ofContent("anything".getBytes(StandardCharsets.UTF_8));
        byte[] whole = deflate("blob 5\0hello".getBytes(StandardCharsets.UTF_8));

        plant(store, id, java.util.Arrays.copyOf(whole, whole.length / 2));

        assertThatThrownBy(() -> store.read(id))
                .as("the existing message, not the new one")
                .isInstanceOf(CorruptObjectException.class)
                .hasMessageContaining("could not be decompressed");
    }

    @Test
    @DisplayName("the limit is comfortably above anything the API can store")
    void limitExceedsWhatCanLegitimatelyExist() {
        // Ten megabytes is the largest file the API accepts. A limit below that
        // would make a legitimate object unreadable, which is the failure mode
        // this bound must not have.
        assertThat(FileSystemObjectStore.MAX_INFLATED_BYTES)
                .isGreaterThan(10L * 1024 * 1024);
    }
}
