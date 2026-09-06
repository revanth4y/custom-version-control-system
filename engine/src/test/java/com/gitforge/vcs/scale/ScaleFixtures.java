package com.gitforge.vcs.scale;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Repositories big enough, and measurement plumbing precise enough, for the
 * V2.0.17 scale work.
 *
 * <p>Separate from the CLI's performance fixtures on purpose. Those measure what
 * a command costs; these measure what the engine costs, at sizes chosen to make
 * a growth curve visible rather than to represent a plausible repository.
 *
 * <p>Everything here is deterministic: fixed author, fixed timestamp, content
 * derived from the loop index. Two runs over the same parameters produce the
 * same object ids, so a measurement can be compared with an earlier one rather
 * than only with itself.
 */
final class ScaleFixtures {

    private ScaleFixtures() {
    }

    /** Fixed, so identical parameters give identical object ids on every run. */
    static final Signature AUTHOR =
            Signature.of("scale", "scale@localhost", Instant.parse("2026-01-01T00:00:00Z"));

    record Fixture(Path root, VcsRepository repository) {
    }

    /** A repository in its own directory, so two fixtures never share storage. */
    static Fixture repository(Path parent, String name, String defaultBranch) throws IOException {
        Path storage = parent.resolve(name);
        Files.createDirectories(storage);
        VcsRepositoryFactory factory = new VcsRepositoryFactory(storage);
        return new Fixture(storage, factory.initialise(RepositoryId.of("repository"), defaultBranch));
    }

    /**
     * A second, independent view of the same storage.
     *
     * <p>The point of the K fixture. {@code VcsRepositoryFactory} keeps its locks
     * in a map of its own, so two factories over one directory hold two different
     * {@code RepositoryLock} instances for the same repository — which is exactly
     * the shape two server processes take.
     */
    static VcsRepository secondView(Fixture fixture) {
        return new VcsRepositoryFactory(fixture.root()).open(RepositoryId.of("repository"));
    }

    /**
     * A linear history of the requested depth, one file rewritten each time.
     *
     * <p>Three objects per commit — a blob, a tree, a commit — so the object count
     * is predictable from the commit count.
     */
    static ObjectId linearHistory(Fixture fixture, String branch, int commits) {
        ObjectId tip = null;
        for (int i = 0; i < commits; i++) {
            tip = fixture.repository().commits().commit(
                    branch,
                    List.of(new FileChange.Put(
                            "file.txt",
                            ("revision " + i + "\n").getBytes(StandardCharsets.UTF_8),
                            FileMode.REGULAR_FILE)),
                    AUTHOR,
                    "Commit " + i);
        }
        return tip;
    }

    /** Creates branches {@code prefix + from} up to {@code prefix + (to - 1)}, all at one commit. */
    static void branches(Fixture fixture, ObjectId at, String prefix, int from, int to) {
        for (int i = from; i < to; i++) {
            fixture.repository().branches().createBranch(prefix + i, at);
        }
    }

    /** Creates lightweight tags {@code prefix + from} up to {@code prefix + (to - 1)}. */
    static void tags(Fixture fixture, ObjectId at, String prefix, int from, int to) {
        for (int i = from; i < to; i++) {
            fixture.repository().tags().createLightweight(prefix + i, at);
        }
    }

    // ------------------------------------------------------------ measurement

    /**
     * Forgets the highest heap occupancy seen so far.
     *
     * <p>Java never lowers a pool's peak on its own, so without this every
     * measurement after the first reports the largest figure of the whole run.
     */
    static void resetPeakHeap() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /** The most heap occupied since the last reset, in megabytes. */
    static long peakHeapMb() {
        long bytes = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                bytes += pool.getPeakUsage().getUsed();
            }
        }
        return bytes / (1024 * 1024);
    }

    /** Runs the work, prints elapsed time and peak heap, and returns the milliseconds. */
    static long timed(String label, Runnable work) {
        resetPeakHeap();
        long started = System.nanoTime();
        work.run();
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("  %-52s %9d ms   peak heap %5d MB%n", label, millis, peakHeapMb());
        return millis;
    }

    static void note(String label, Object value) {
        System.out.printf("  %-52s %9s%n", label, value);
    }
}
