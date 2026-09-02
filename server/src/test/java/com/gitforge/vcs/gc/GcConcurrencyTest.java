package com.gitforge.vcs.gc;

import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Collection against live writes.
 *
 * <p>This is the case the feature is most able to get wrong, and the one least
 * likely to show up by accident. A commit writes its blobs, then its trees, then
 * the commit object, and only afterwards moves the branch — so for the length of
 * that sequence everything it has written is unreferenced and looks exactly like
 * garbage. A sweep that ran in the middle would delete live work and leave the
 * branch update pointing at a commit whose tree had gone.
 *
 * <p><strong>What this test does and does not prove.</strong> It runs a sweeper
 * flat out against a stream of commits and asserts that nothing is ever collected
 * and nothing is ever lost. That is worth having — it exercises the real wiring
 * under real contention — but it is a race, and it was measured: with the shared
 * lock removed from {@code CommitService} this test still passes, because the
 * window is microseconds wide and a sweep rarely lands inside it. It is therefore
 * a smoke test, not the safety proof.
 *
 * <p>The safety proof is
 * {@code com.gitforge.vcs.repository.GcWriteWindowTest}, which stops a writer
 * inside the window instead of hoping to arrive there, and which does fail when
 * the lock is removed.
 *
 * <p>Repositories are opened through {@link VcsRepositoryFactory} rather than
 * built directly, because the property under test is that separately-opened
 * handles to one repository share a lock. Two handles with private locks would
 * pass every other test in the suite and fail this one.
 */
class GcConcurrencyTest {

    private static final int COMMITS = 60;
    private static final int SWEEPS = 60;

    @TempDir
    Path storageRoot;

    private VcsRepositoryFactory factory;
    private RepositoryId id;

    @BeforeEach
    void setUp() {
        factory = new VcsRepositoryFactory(storageRoot);
        id = RepositoryId.of("concurrent");
        factory.initialise(id, "main");
        commit(factory.open(id), 0);
    }

    private static Signature author(int sequence) {
        return new Signature(
                "Ada Lovelace",
                "ada@example.com",
                Instant.ofEpochSecond(1_700_000_000L + sequence),
                ZoneOffset.UTC);
    }

    private static ObjectId commit(VcsRepository repository, int sequence) {
        return repository.commits().commit(
                "main",
                List.of(FileChange.put(
                        "counter.txt",
                        ("value " + sequence + "\n").getBytes(StandardCharsets.UTF_8))),
                author(sequence),
                "Commit number " + sequence);
    }

    @Test
    @DisplayName("a sweep running against a stream of commits collects nothing and loses nothing")
    void concurrentCommitsAndSweepsNeverLoseAnObject() throws Exception {
        List<ObjectId> written = new CopyOnWriteArrayList<>();
        AtomicInteger collectedTotal = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);

        Callable<Void> writer = () -> {
            start.await();
            // A fresh handle per commit, as a request would get.
            for (int sequence = 1; sequence <= COMMITS; sequence++) {
                written.add(commit(factory.open(id), sequence));
            }
            return null;
        };

        Callable<Void> sweeper = () -> {
            start.await();
            for (int run = 0; run < SWEEPS; run++) {
                try {
                    GcReport report = factory.open(id).gc().collect();
                    collectedTotal.addAndGet(report.collected().size());
                } catch (RuntimeException ex) {
                    failures.add(ex);
                }
            }
            return null;
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> writing = pool.submit(writer);
            Future<Void> sweeping = pool.submit(sweeper);
            start.countDown();
            writing.get(60, TimeUnit.SECONDS);
            sweeping.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).isEmpty();

        // Nothing was ever collectible: every object belonged to a commit that was
        // either finished, or in flight and therefore protected.
        assertThat(collectedTotal.get()).isZero();

        // Every commit is still whole - the commit, its tree, and its blobs.
        VcsRepository repository = factory.open(id);
        assertThat(written).hasSize(COMMITS);
        for (ObjectId commit : written) {
            assertThat(repository.objects().contains(commit)).isTrue();
            ObjectId tree = repository.objects().readCommit(commit).tree();
            assertThat(repository.objects().contains(tree)).isTrue();
        }

        // And the branch still reaches all of them, in order.
        assertThat(repository.branches().getBranch("main")).contains(written.getLast());
        assertThat(repository.reader().history("main", COMMITS + 10)).hasSize(COMMITS + 1);

        // A final sweep on the quiet repository confirms the store is consistent.
        GcReport settled = factory.open(id).gc().collect();
        assertThat(settled.collected()).isEmpty();
        assertThat(settled.reachableObjects()).isEqualTo(settled.storedObjects());
    }

    @Test
    @DisplayName("two sweeps running at once neither collide nor double-count")
    void concurrentSweepsAreSafe() throws Exception {
        // Real garbage, so the sweeps have something to race over.
        VcsRepository repository = factory.open(id);
        ObjectId mainTip = repository.branches().getBranch("main").orElseThrow();
        ObjectId stranded = commit(repository, 99);

        // Move the branch back off it, so the commit is genuinely unreferenced.
        repository.branches().updateBranch("main", mainTip);
        assertThat(repository.gc().report().unreachable())
                .extracting(UnreachableObject::id)
                .contains(stranded);

        CountDownLatch start = new CountDownLatch(1);
        List<GcReport> reports = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        Callable<Void> sweep = () -> {
            start.await();
            try {
                reports.add(factory.open(id).gc().collect());
            } catch (RuntimeException ex) {
                failures.add(ex);
            }
            return null;
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = pool.submit(sweep);
            Future<Void> second = pool.submit(sweep);
            start.countDown();
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).isEmpty();
        assertThat(reports).hasSize(2);

        // Exactly one sweep removed the stranded commit. Deletion is idempotent,
        // so the loser reports it as already gone rather than failing.
        long timesCollected = reports.stream()
                .flatMap(report -> report.collected().stream())
                .filter(stranded::equals)
                .count();
        assertThat(timesCollected).isEqualTo(1);
        assertThat(factory.open(id).objects().contains(stranded)).isFalse();

        // The repository is still whole.
        VcsRepository after = factory.open(id);
        assertThat(after.branches().getBranch("main")).isPresent();
        assertThat(after.gc().report().unreachable()).isEmpty();
    }
}
