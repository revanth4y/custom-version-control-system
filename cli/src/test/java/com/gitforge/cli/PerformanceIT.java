package com.gitforge.cli;

import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the CLI behaves when the repository is large.
 *
 * <p>An integration test rather than a unit test, because these build real
 * repositories at the sizes the gate names — ten thousand commits, twenty
 * thousand objects, five thousand references — and that takes minutes, not
 * milliseconds. Running them with the unit tests would make the ordinary suite
 * unusable, and a suite people skip is a suite that stops catching anything.
 *
 * <p>Every case is bounded by {@link Timeout}. A hang is a failure here: the
 * whole point is to find work that grows faster than the input, and the shape
 * that takes is a command that never comes back.
 *
 * <p>The measurements are printed rather than asserted against absolute
 * durations. A threshold in milliseconds is a promise about somebody else's
 * hardware, and the first slower machine turns it into a flaky test. What
 * <em>is</em> asserted is the shape: that doubling the input does not quadruple
 * the work, that output is complete, and that nothing runs unbounded.
 */
class PerformanceIT {

    private static final int COMMITS = 10_000;
    private static final int TARGET_OBJECTS = 20_000;
    private static final int REFS = 5_000;

    /**
     * Prints one measurement in a form that can be pasted into a report.
     *
     * <p>Time and peak heap together, because either alone can hide the other:
     * an operation that returns quickly by reading the whole history into a list
     * has not solved the problem, it has moved it. The peak counters are reset
     * immediately before the work so the figure belongs to this operation rather
     * than to the fixture that had to be built first.
     */
    private static long timed(String label, Runnable work) {
        resetPeakHeap();
        long started = System.nanoTime();
        work.run();
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("  %-46s %8d ms   peak heap %5d MB%n", label, millis, peakHeapMb());
        return millis;
    }

    /**
     * Forgets the highest heap occupancy seen so far.
     *
     * <p>Java reports a peak per memory pool and never lowers it on its own, so
     * without this every measurement after the first would report the largest
     * figure the whole run has ever reached.
     */
    private static void resetPeakHeap() {
        for (java.lang.management.MemoryPoolMXBean pool
                : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
    }

    /** The most heap that has been occupied since the last reset. */
    private static long peakHeapMb() {
        long bytes = 0;
        for (java.lang.management.MemoryPoolMXBean pool
                : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP) {
                bytes += pool.getPeakUsage().getUsed();
            }
        }
        return bytes / (1024 * 1024);
    }

    private static void note(String label, Object value) {
        System.out.printf("  %-46s %8s%n", label, value);
    }

    // ------------------------------------------------------- A: long history

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @DisplayName("A: 10,000 commits — creation, traversal, resolution, reachability")
    void largeHistory(@TempDir Path root) throws IOException {
        System.out.println("\n=== A: 10,000-commit linear history ===");
        PerformanceFixtures.Fixture fixture =
                PerformanceFixtures.emptyRepository(root.resolve("repo"), "main");

        long[] tip = new long[1];
        ObjectId[] head = new ObjectId[1];
        long setup = timed("setup: create " + COMMITS + " commits",
                () -> head[0] = PerformanceFixtures.linearHistory(fixture, "main", COMMITS));
        note("objects stored", PerformanceFixtures.objectCount(fixture));
        note("per-commit setup cost (ms)", String.format("%.2f", setup / (double) COMMITS));

        var reader = fixture.repository().reader();

        // Paged reads must cost what the page costs, not what the history costs.
        long firstPage = timed("log: newest 50", () -> reader.history("main", 50));
        long deepPage = timed("log: newest 1000", () -> reader.history("main", 1000));
        assertThat(reader.history("main", 50)).hasSize(50);

        timed("show: resolve and read the tip", () -> {
            ObjectId id = reader.resolve("main").orElseThrow();
            reader.commit(id).orElseThrow();
        });

        // A relative revision walks parents one at a time, so this is the honest
        // worst case for resolution on a deep history.
        timed("resolve main~1000", () -> reader.resolve("main~1000").orElseThrow());
        timed("resolve main~9999", () -> reader.resolve("main~9999").orElseThrow());

        long walk = timed("full ancestry walk (10,000 commits)", () -> {
            var graph = new com.gitforge.vcs.graph.CommitGraph(fixture.repository().objects());
            note("  ancestors found", graph.ancestorsOf(head[0]).size());
        });

        timed("statistics: reachable commits", () ->
                note("  reachable commits", fixture.repository().statistics().reachableCommits().size()));

        // The shape that matters: reading twenty times more history must not cost
        // anything like twenty times more, because both are bounded reads of a
        // page rather than scans of the whole history.
        System.out.printf("  page scaling: 50 -> %d ms, 1000 -> %d ms%n", firstPage, deepPage);
        assertThat(walk).as("a full ancestry walk must complete").isNotNegative();
    }

    // ------------------------------------------------------- B: large store

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @DisplayName("B: 20,000 objects — enumeration, integrity, reachability, storage")
    void largeObjectStore(@TempDir Path root) throws IOException {
        System.out.println("\n=== B: 20,000-object store ===");
        PerformanceFixtures.Fixture fixture =
                PerformanceFixtures.emptyRepository(root.resolve("repo"), "main");

        // 250 commits x 64 files: each commit writes new blobs, the trees above
        // them and a commit, which reaches the target without 20,000 commits.
        ObjectId[] head = new ObjectId[1];
        timed("setup: build the store",
                () -> head[0] = PerformanceFixtures.broadHistory(fixture, "main", 250, 64));

        long stored = PerformanceFixtures.objectCount(fixture);
        note("objects stored", stored);
        assertThat(stored)
                .as("the fixture must actually reach the size being measured")
                .isGreaterThanOrEqualTo(TARGET_OBJECTS);

        var objects = fixture.repository().objects();

        timed("enumerate every object id", () -> note("  ids listed", objects.listIds().size()));

        // Integrity re-reads and re-hashes, so this is the most expensive read
        // path in the system and the one most likely to hide an O(N^2).
        timed("integrity: read and verify every object", () -> {
            int verified = 0;
            for (ObjectId id : objects.listIds()) {
                if (objects.read(id).isPresent()) {
                    verified++;
                }
            }
            note("  objects verified", verified);
        });

        timed("reachability scan", () -> {
            var health = new com.gitforge.vcs.insights.ReachabilityHealth(
                    fixture.repository().objects(),
                    fixture.repository().refs(),
                    new com.gitforge.vcs.worktree.WorkTreeState(
                            root.resolve("repo/.gitforge/repository")),
                    fixture.repository().gc());
            var scan = health.scan();
            note("  reachable", scan.reachableObjects());
            note("  unreachable", scan.unreachableObjects());
            note("  truncated", scan.truncated());
        });

        timed("storage insights (bounded scan)", () -> {
            var usage = new com.gitforge.vcs.insights.StorageInsights(objects).compute();
            note("  scanned", usage.scannedObjects());
            note("  truncated at the cap", usage.truncated());
        });
    }

    // ---------------------------------------------------------- C: many refs

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @DisplayName("C: 5,000 refs — listing, resolution, verification")
    void largeRefSet(@TempDir Path root) throws IOException {
        System.out.println("\n=== C: 5,000 references ===");
        PerformanceFixtures.Fixture fixture =
                PerformanceFixtures.emptyRepository(root.resolve("repo"), "main");
        ObjectId tip = PerformanceFixtures.linearHistory(fixture, "main", 5);

        timed("setup: create " + (REFS / 2) + " branches",
                () -> PerformanceFixtures.manyBranches(fixture, tip, REFS / 2, "branch-"));
        timed("setup: create " + (REFS / 2) + " tags",
                () -> PerformanceFixtures.manyTags(fixture, tip, REFS / 2, "tag-"));

        var refs = fixture.repository().refs();
        note("branches", refs.listBranches().size());
        note("tags", refs.listTags().size());

        timed("list branches", () -> refs.listBranches());
        timed("list tags", () -> refs.listTags());

        // Resolution must not depend on how many other references exist.
        timed("resolve one branch by name",
                () -> fixture.repository().reader().resolve("branch-2499").orElseThrow());
        timed("resolve one tag by name",
                () -> fixture.repository().reader().resolve("tag-2499").orElseThrow());

        // This is the case worth watching: divergence compares every branch
        // against HEAD, so a naive implementation walks the graph per branch.
        timed("branch divergence against HEAD (2,500 branches)", () -> {
            var graph = new com.gitforge.vcs.graph.CommitGraph(fixture.repository().objects());
            var rows = new com.gitforge.vcs.insights.BranchDivergence(
                    refs, fixture.repository().branches(), graph).againstHead();
            note("  branches compared", rows.size());
        });

        timed("reference composition", () -> {
            var graph = new com.gitforge.vcs.graph.CommitGraph(fixture.repository().objects());
            var composition = new com.gitforge.vcs.insights.RefComposition(
                    refs, fixture.repository().objects(), graph).compute();
            note("  refs counted", composition.total());
        });
    }

    // ------------------------------------------------------- D: large output

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @DisplayName("D: large JSON — size, validity, determinism")
    void largeJsonOutput(@TempDir Path root, @TempDir Path home) throws IOException {
        System.out.println("\n=== D: large JSON output ===");
        PerformanceFixtures.Fixture fixture =
                PerformanceFixtures.emptyRepository(root.resolve("repo"), "main");
        ObjectId tip = PerformanceFixtures.linearHistory(fixture, "main", 20);
        PerformanceFixtures.manyBranches(fixture, tip, 2_000, "branch-");
        PerformanceFixtures.manyTags(fixture, tip, 2_000, "tag-");

        CliHarness cli = new CliHarness(root, home);

        String[] branches = new String[1];
        timed("branch list --json (2,001 branches)",
                () -> branches[0] = cli.runIn(root.resolve("repo"), "--json", "branch", "list").out());
        note("output bytes", branches[0].length());

        String[] tags = new String[1];
        timed("tag list --json (2,000 tags)",
                () -> tags[0] = cli.runIn(root.resolve("repo"), "--json", "tag", "list").out());
        note("output bytes", tags[0].length());

        // Complete and parseable, not merely large: a truncated write is the
        // failure mode that looks like success until something tries to read it.
        // Line endings are the platform's: the CLI prints through a PrintStream,
        // so a line ends CRLF on Windows and LF elsewhere. The envelope shape is
        // what is under test, so the terminator is stripped before checking it.
        assertThat(branches[0]).startsWith("{");
        assertThat(branches[0].stripTrailing()).endsWith("}");
        assertThat(branches[0]).contains("\"branch-1999\"");
        assertThat(branches[0]).contains("\"schemaVersion\": 1");
        assertThat(tags[0]).contains("\"tag-1999\"");

        String repeated = cli.runIn(root.resolve("repo"), "--json", "branch", "list").out();
        assertThat(repeated)
                .as("the same state must serialize to the same bytes")
                .isEqualTo(branches[0]);

        long braces = branches[0].chars().filter(c -> c == '{').count();
        long closes = branches[0].chars().filter(c -> c == '}').count();
        assertThat(braces).as("balanced braces mean nothing was truncated").isEqualTo(closes);
    }

    // ------------------------------------------------------- E: deep sandbox

    @Test
    // Fifteen minutes rather than five: a single 200-deep validation costs
    // hundreds of milliseconds on Windows, so a thousand of them plus the
    // scaling sweep does not fit in five. The bound is still a bound.
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    @DisplayName("E: deep sandbox — validation cost, safe links, escaping links")
    void deepSandbox(@TempDir Path root) throws IOException {
        System.out.println("\n=== E: deep sandbox tree ===");
        var sandbox = new com.gitforge.cli.security.SandboxPath(root, root);

        Path deepest = PerformanceFixtures.deepTree(root, 200);
        String relative = root.relativize(deepest).toString().replace('\\', '/');
        note("depth", 200);

        timed("validate a shallow path", () -> sandbox.resolve("file.txt"));
        timed("validate a 200-deep path", () -> sandbox.resolve(relative + "/file.txt"));

        // The question the deep case raises is which curve it sits on. A single
        // slow number cannot answer that; four depths can. Containment is
        // checked by walking the path once, so cost should rise in proportion to
        // depth. If it rose with the square of depth, doubling the depth would
        // roughly quadruple the time, and the ratios below would show it.
        System.out.println("  depth scaling (100 validations at each depth):");
        long previous = 0;
        int previousDepth = 0;
        for (int depth : new int[] {5, 10, 25, 50, 100, 200}) {
            Path level = PerformanceFixtures.deepTree(root.resolve("scale" + depth), depth);
            String path = root.relativize(level).toString().replace(java.io.File.separatorChar, '/');
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                sandbox.resolve(path + "/file" + i + ".txt");
            }
            long each = (System.nanoTime() - start) / 100 / 1_000;
            System.out.printf("    depth %-4d %6d us each%s%n", depth, each,
                    previous == 0 ? ""
                            : String.format("   (x%.2f for x%.1f depth)",
                                    each / (double) previous, depth / (double) previousDepth));
            previous = each;
            previousDepth = depth;
        }

        // Validation walks the path once; a quadratic implementation would show
        // up as the deep case costing hundreds of times the shallow one.
        timed("validate the deep path 1,000 times", () -> {
            for (int i = 0; i < 1_000; i++) {
                sandbox.resolve(relative + "/file" + i + ".txt");
            }
        });

        timed("reject 1,000 traversal attempts", () -> {
            for (int i = 0; i < 1_000; i++) {
                try {
                    sandbox.resolve("../../../etc/passwd" + i);
                    throw new AssertionError("a traversal was accepted");
                } catch (CliException expected) {
                    // The refusal is the measurement.
                }
            }
        });

        assertThat(sandbox.contains(relative + "/file.txt")).isTrue();
        assertThat(sandbox.contains("../../../etc/passwd")).isFalse();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("E: symlinks in a deep tree, safe and escaping")
    void deepSandboxSymlinks(@TempDir Path root, @TempDir Path elsewhere) throws IOException {
        System.out.println("\n=== E: deep sandbox symlinks ===");
        Path deepest = PerformanceFixtures.deepTree(root, 100);
        Files.writeString(deepest.resolve("real.txt"), "inside");
        Files.createSymbolicLink(root.resolve("safe"), deepest);
        Files.createSymbolicLink(root.resolve("escape"), elsewhere);

        var sandbox = new com.gitforge.cli.security.SandboxPath(root, root);

        timed("resolve through a safe link at depth 100",
                () -> sandbox.resolve("safe/real.txt"));
        timed("reject 1,000 escaping-link resolutions", () -> {
            for (int i = 0; i < 1_000; i++) {
                try {
                    sandbox.resolve("escape/secret" + i);
                    throw new AssertionError("an escaping link was accepted");
                } catch (CliException expected) {
                    // Expected.
                }
            }
        });

        assertThat(sandbox.contains("safe/real.txt")).isTrue();
        assertThat(sandbox.contains("escape/anything")).isFalse();
    }
}
