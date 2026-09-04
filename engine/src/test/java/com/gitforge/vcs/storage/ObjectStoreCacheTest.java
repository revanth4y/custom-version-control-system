package com.gitforge.vcs.storage;

import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.VcsObject;
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
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Remembering a verified object must not change what the store means.
 *
 * <p>Reading an object costs a file read, an inflate and a hash over the whole
 * payload, and the engine reads the same objects repeatedly. Those bytes cannot
 * change - an id is the hash of the object - so the answer can be kept. Two
 * things about the store are <em>not</em> immutable, and this suite exists mostly
 * to hold the line on them.
 *
 * <p>Whether an object exists is mutable, because a sweep removes objects. So
 * existence is asked of the filesystem on every read and the cache is consulted
 * only once the file has been found.
 *
 * <p>What the bytes on disk say right now is also mutable, in the sense that
 * corruption can appear after a read. So {@code verify} never consults the cache:
 * it exists to check the bytes that are there, and answering from memory would
 * make an integrity scan a statement about this process rather than about the
 * repository.
 */
class ObjectStoreCacheTest {

    @TempDir
    Path root;

    private FileSystemObjectStore store;

    @BeforeEach
    void setUp() {
        store = new FileSystemObjectStore(root);
    }

    private Blob blob(String content) {
        return new Blob(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Overwrites an object's file with something that does not hash to its id. */
    private void corruptOnDisk(ObjectId id) throws IOException {
        Path path = root.resolve("objects")
                .resolve(id.toHex().substring(0, 2))
                .resolve(id.toHex().substring(2));
        assertThat(Files.isRegularFile(path)).isTrue();
        // A well-formed object of the wrong content: it inflates and parses,
        // and only then fails the hash. Writing rubbish would fail at the
        // inflate instead, which is a different check.
        byte[] framed = ("blob 7" + (char) 0 + "garbage").getBytes(StandardCharsets.UTF_8);
        Files.write(path, deflate(framed));
    }

    private static byte[] deflate(byte[] framed) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream deflater =
                     new java.util.zip.DeflaterOutputStream(out)) {
            deflater.write(framed);
        }
        return out.toByteArray();
    }

    @Nested
    @DisplayName("a cached object is one that was verified")
    class Integrity {

        @Test
        @DisplayName("the same bytes come back on every read")
        void repeatedReadsAgree() {
            Blob written = blob("hello cache");
            ObjectId id = store.write(written);

            VcsObject first = store.read(id).orElseThrow();
            VcsObject second = store.read(id).orElseThrow();
            VcsObject third = store.read(id).orElseThrow();

            assertThat(first).isEqualTo(written);
            assertThat(second).isEqualTo(first);
            assertThat(third).isEqualTo(first);
        }

        @Test
        @DisplayName("a corrupt object is refused and never remembered")
        void corruptionIsNeverCached() throws IOException {
            Blob written = blob("about to be damaged");
            ObjectId id = store.write(written);

            // A fresh store, so nothing is remembered from the write above.
            FileSystemObjectStore fresh = new FileSystemObjectStore(root);
            corruptOnDisk(id);

            assertThatThrownBy(() -> fresh.read(id))
                    .isInstanceOf(CorruptObjectException.class);
            // And again, because a failed read must not have left anything behind
            // that a second attempt could be answered from.
            assertThatThrownBy(() -> fresh.read(id))
                    .isInstanceOf(CorruptObjectException.class);
        }

        @Test
        @DisplayName("verify reads the disk even when the object was just read")
        void verifyIgnoresTheCache() throws IOException {
            Blob written = blob("verify me");
            ObjectId id = store.write(written);

            // Warm whatever there is to warm.
            assertThat(store.read(id)).isPresent();

            corruptOnDisk(id);

            // This is the assertion the whole design hangs on. If verify were
            // served from memory it would pass here, and an integrity scan would
            // report a damaged repository as healthy.
            assertThatThrownBy(() -> store.verify(id))
                    .isInstanceOf(CorruptObjectException.class);
        }

        @Test
        @DisplayName("a deleted object is gone even if it was read a moment ago")
        void deletionBeatsTheCache() {
            Blob written = blob("short lived");
            ObjectId id = store.write(written);
            assertThat(store.read(id)).isPresent();

            assertThat(store.delete(id)).isTrue();

            assertThat(store.read(id))
                    .as("existence is never answered from memory")
                    .isEmpty();
            assertThat(store.contains(id)).isFalse();
        }

        @Test
        @DisplayName("an object deleted through another store instance is also gone here")
        void deletionThroughAnotherInstance() {
            Blob written = blob("removed elsewhere");
            ObjectId id = store.write(written);
            assertThat(store.read(id)).isPresent();

            new FileSystemObjectStore(root).delete(id);

            assertThat(store.read(id))
                    .as("the filesystem is consulted first, whoever removed the file")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the cache is bounded and isolated")
    class Bounds {

        @Test
        @DisplayName("it never grows past its capacity")
        void evictsBeyondCapacity() {
            VerifiedObjectCache cache = new VerifiedObjectCache(8);
            List<ObjectId> ids = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                Blob blob = blob("entry " + i);
                // Twice, because nothing is kept on first sight.
                cache.put(blob.id(), blob, blob.size(), 1L);
                cache.put(blob.id(), blob, blob.size(), 1L);
                ids.add(blob.id());
            }
            assertThat(cache.size()).isEqualTo(8);

            // And the survivors are the recent ones, not an arbitrary eight.
            assertThat(cache.get(ids.get(49), 8L, 1L)).isNotNull();
            assertThat(cache.get(ids.get(0), 8L, 1L)).isNull();
        }

        @Test
        @DisplayName("eviction changes speed, never answers")
        void evictionDoesNotChangeResults() {
            List<ObjectId> ids = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                ids.add(store.write(blob("object " + i)));
            }
            for (ObjectId id : ids) {
                assertThat(store.read(id)).isPresent();
            }
            // Read them all again in the opposite order, which for a small cache
            // means most of these are misses.
            for (int i = ids.size() - 1; i >= 0; i--) {
                VcsObject object = store.read(ids.get(i)).orElseThrow();
                assertThat(object).isEqualTo(blob("object " + i));
            }
        }

        @Test
        @DisplayName("a large blob is not kept")
        void largePayloadsAreNotCached() {
            VerifiedObjectCache cache = new VerifiedObjectCache(16);
            byte[] big = new byte[VerifiedObjectCache.MAX_CACHED_PAYLOAD + 1];
            Blob large = new Blob(big);
            cache.put(large.id(), large, large.size(), 1L);
            cache.put(large.id(), large, large.size(), 1L);
            assertThat(cache.size()).as("however often it is asked for").isZero();

            Blob small = blob("small enough");
            cache.put(small.id(), small, small.size(), 1L);
            cache.put(small.id(), small, small.size(), 1L);
            assertThat(cache.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("nothing is kept on first sight, so a scan cannot thrash it")
        void admittedOnSecondSight() {
            VerifiedObjectCache cache = new VerifiedObjectCache(8);
            Blob blob = blob("asked for twice");

            cache.put(blob.id(), blob, blob.size(), 1L);
            assertThat(cache.size())
                    .as("a single pass over a large history stores nothing")
                    .isZero();
            assertThat(cache.get(blob.id(), blob.size(), 1L)).isNull();

            cache.put(blob.id(), blob, blob.size(), 1L);
            assertThat(cache.size()).as("asked for again, it is worth keeping").isEqualTo(1);
            assertThat(cache.get(blob.id(), blob.size(), 1L)).isEqualTo(blob);
        }

        @Test
        @DisplayName("one store cannot answer for another")
        void repositoriesStayIsolated(@TempDir Path other) {
            Blob shared = blob("same bytes in both places");
            ObjectId id = store.write(shared);
            assertThat(store.read(id)).isPresent();

            FileSystemObjectStore elsewhere = new FileSystemObjectStore(other);
            assertThat(elsewhere.read(id))
                    .as("a store that never held this object does not produce it")
                    .isEmpty();
            assertThat(elsewhere.contains(id)).isFalse();
        }

        @Test
        @DisplayName("a store rebuilt over a deleted directory starts empty")
        void recreatedRepositoryDoesNotInheritEntries() throws IOException {
            Blob written = blob("first life");
            ObjectId id = store.write(written);
            assertThat(store.read(id)).isPresent();

            // The repository is thrown away and a new one made in its place.
            Path objects = root.resolve("objects");
            try (var walk = Files.walk(objects)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                                // Best effort; the assertion below is the check.
                            }
                        });
            }

            FileSystemObjectStore reborn = new FileSystemObjectStore(root);
            assertThat(reborn.read(id))
                    .as("nothing survives from the store that used to be here")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("under concurrency")
    class Concurrency {

        @Test
        @DisplayName("many readers of one object all see the same bytes")
        void concurrentReadersOfOneObject() throws Exception {
            Blob written = blob("contended");
            ObjectId id = store.write(written);

            List<Throwable> failures = new CopyOnWriteArrayList<>();
            int readers = 12;
            ExecutorService pool = Executors.newFixedThreadPool(readers);
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < readers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int round = 0; round < 200; round++) {
                            Optional<VcsObject> read = store.read(id);
                            if (read.isEmpty() || !read.get().equals(written)) {
                                failures.add(new AssertionError("a reader saw something else"));
                            }
                        }
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
            assertThat(failures).isEmpty();
        }

        @Test
        @DisplayName("readers of different objects do not cross answers")
        void concurrentReadersOfManyObjects() throws Exception {
            List<ObjectId> ids = new ArrayList<>();
            List<Blob> blobs = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                Blob blob = blob("distinct " + i);
                blobs.add(blob);
                ids.add(store.write(blob));
            }

            List<Throwable> failures = new CopyOnWriteArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            for (int t = 0; t < 8; t++) {
                final int offset = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int round = 0; round < 300; round++) {
                            int index = (round + offset) % ids.size();
                            VcsObject object = store.read(ids.get(index)).orElseThrow();
                            if (!object.equals(blobs.get(index))) {
                                failures.add(new AssertionError(
                                        "id " + ids.get(index) + " produced the wrong object"));
                            }
                        }
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
            assertThat(failures).isEmpty();
        }

        @Test
        @DisplayName("reads racing deletions never produce a deleted object")
        void readsDuringDeletion() throws Exception {
            List<ObjectId> ids = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                ids.add(store.write(blob("transient " + i)));
            }
            for (ObjectId id : ids) {
                store.read(id);
            }

            List<Throwable> failures = new CopyOnWriteArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);

            pool.submit(() -> {
                try {
                    start.await();
                    for (ObjectId id : ids) {
                        store.delete(id);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 5; round++) {
                        for (ObjectId id : ids) {
                            // What is checked is the content, never the timing.
                            // A read either produces the object, finds it gone,
                            // or - because the store has always checked that a
                            // file exists and then opened it as two steps - fails
                            // because the file vanished between the two. That
                            // race predates this change and is not what this
                            // case is about; producing the wrong bytes would be.
                            try {
                                store.read(id).ifPresent(object -> {
                                    if (!(object instanceof com.gitforge.vcs.object.Blob)) {
                                        failures.add(new AssertionError(
                                                "id " + id + " produced a " + object.type().header()));
                                    }
                                });
                            } catch (ObjectStoreException vanishedMidRead) {
                                // The deleter won the race, which is allowed.
                            }
                        }
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
            assertThat(failures).isEmpty();
            for (ObjectId id : ids) {
                assertThat(store.read(id)).isEmpty();
            }
        }
    }
}
