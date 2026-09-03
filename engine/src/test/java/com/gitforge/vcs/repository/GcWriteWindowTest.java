package com.gitforge.vcs.repository;

import com.gitforge.vcs.gc.GarbageCollector;
import com.gitforge.vcs.gc.GcReport;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.ObjectType;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.object.VcsObject;
import com.gitforge.vcs.ref.FileSystemRefStore;
import com.gitforge.vcs.ref.RefStore;
import com.gitforge.vcs.storage.FileSystemObjectStore;
import com.gitforge.vcs.storage.ObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write window, held open on purpose.
 *
 * <p>A commit writes its blobs, its trees and then the commit object, and only
 * afterwards moves the branch. Between the last write and the reference moving,
 * everything it has just stored is unreferenced and indistinguishable from
 * garbage by inspection alone.
 *
 * <p>Racing a sweeper against ordinary commits does not reliably land inside that
 * window — it is a few microseconds wide, and a sweep that has to enumerate the
 * store will almost always arrive too early or too late. A test that only
 * <em>usually</em> reproduces a data-loss bug is a test that will report the bug
 * as fixed. So this one does not race: it stops the writer inside the window and
 * starts the sweep there.
 *
 * <p>Verified to be capable of failing. With the shared lock removed from
 * {@code CommitService}, this test collects the in-flight commit and fails on the
 * assertion below; with it restored, it passes.
 */
class GcWriteWindowTest {

    /** Milliseconds the writer is held inside the window, before the branch moves. */
    private static final long HOLD_MILLIS = 500;

    @TempDir
    Path repositoryRoot;

    private RepositoryLock lock;
    private PausingObjectStore objects;
    private RefStore refs;
    private VcsRepository repository;
    private GarbageCollector collector;

    @BeforeEach
    void setUp() {
        lock = new RepositoryLock();
        objects = new PausingObjectStore(new FileSystemObjectStore(repositoryRoot));
        refs = new FileSystemRefStore(repositoryRoot);
        repository = new VcsRepository(RepositoryId.of("window"), objects, refs, lock);
        collector = new GarbageCollector(objects, refs, null, lock);

        commit(1);
    }

    private ObjectId commit(int sequence) {
        return repository.commits().commit(
                "main",
                List.of(FileChange.put(
                        "counter.txt",
                        ("value " + sequence + "\n").getBytes(StandardCharsets.UTF_8))),
                new Signature("Ada Lovelace", "ada@example.com",
                        Instant.ofEpochSecond(1_700_000_000L + sequence), ZoneOffset.UTC),
                "Commit number " + sequence);
    }

    @Test
    @DisplayName("a sweep started inside the write window collects nothing and loses nothing")
    void aCommitInFlightIsNeverCollected() throws Exception {
        ObjectId before = repository.branches().getBranch("main").orElseThrow();

        // From here, writing a commit object signals and then holds.
        objects.holdAfterCommitWrite(HOLD_MILLIS);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ObjectId> writing = pool.submit(() -> commit(2));

            // Start sweeping the moment the commit object is on disk and the
            // branch has not moved. Every object of that commit is unreachable
            // right now.
            assertThat(objects.awaitInsideWindow(10, TimeUnit.SECONDS)).isTrue();
            Future<GcReport> sweeping = pool.submit(() -> collector.collect());

            ObjectId created = writing.get(30, TimeUnit.SECONDS);
            GcReport report = sweeping.get(30, TimeUnit.SECONDS);

            // The assertion that would fail without the lock.
            assertThat(report.collected()).isEmpty();
            assertThat(report.unreachable()).isEmpty();

            // And the commit is whole: itself, its tree, and the blob beneath it.
            assertThat(created).isNotEqualTo(before);
            assertThat(objects.contains(created)).isTrue();
            ObjectId tree = objects.readCommit(created).tree();
            assertThat(objects.contains(tree)).isTrue();
            assertThat(objects.readTree(tree).entries()).isNotEmpty();
            objects.readTree(tree).entries()
                    .forEach(entry -> assertThat(objects.contains(entry.id())).isTrue());

            assertThat(repository.branches().getBranch("main")).contains(created);
            assertThat(repository.reader().history("main", 10)).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A store that stops inside the write window.
     *
     * <p>Delegates everything. The one difference is that writing a commit object
     * releases a latch and then sleeps, which leaves the caller part-way through
     * {@code CommitService} — objects durable, reference not yet moved — for as
     * long as the test needs.
     */
    private static final class PausingObjectStore implements ObjectStore {

        private final ObjectStore delegate;
        private final CountDownLatch insideWindow = new CountDownLatch(1);
        private volatile long holdMillis;

        private PausingObjectStore(ObjectStore delegate) {
            this.delegate = delegate;
        }

        void holdAfterCommitWrite(long millis) {
            this.holdMillis = millis;
        }

        boolean awaitInsideWindow(long timeout, TimeUnit unit) throws InterruptedException {
            return insideWindow.await(timeout, unit);
        }

        @Override
        public ObjectId write(VcsObject object) {
            ObjectId id = delegate.write(object);
            if (holdMillis > 0 && object.type() == ObjectType.COMMIT) {
                insideWindow.countDown();
                try {
                    Thread.sleep(holdMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return id;
        }

        @Override
        public Optional<VcsObject> read(ObjectId id) {
            return delegate.read(id);
        }

        @Override
        public com.gitforge.vcs.object.Blob readBlob(ObjectId id) {
            return delegate.readBlob(id);
        }

        @Override
        public com.gitforge.vcs.object.Tree readTree(ObjectId id) {
            return delegate.readTree(id);
        }

        @Override
        public com.gitforge.vcs.object.Commit readCommit(ObjectId id) {
            return delegate.readCommit(id);
        }

        @Override
        public boolean contains(ObjectId id) {
            return delegate.contains(id);
        }

        @Override
        public void verify(ObjectId id) {
            delegate.verify(id);
        }

        @Override
        public long count() {
            return delegate.count();
        }

        @Override
        public List<ObjectId> findByPrefix(String hexPrefix) {
            return delegate.findByPrefix(hexPrefix);
        }

        @Override
        public List<ObjectId> listIds() {
            return delegate.listIds();
        }

        @Override
        public long sizeOf(ObjectId id) {
            return delegate.sizeOf(id);
        }

        @Override
        public boolean delete(ObjectId id) {
            return delegate.delete(id);
        }

        @Override
        public List<String> temporaryFiles() {
            return delegate.temporaryFiles();
        }
    }
}
