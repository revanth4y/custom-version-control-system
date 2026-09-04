package com.gitforge.vcs.scale;

import com.gitforge.vcs.graph.CommitGraph;
import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What object access costs at scale, and where the cost actually is.
 *
 * <p>Two questions. Reading an object costs a file read, an inflate and a hash
 * over the payload, and the engine reads the same objects repeatedly - so what
 * does remembering the verified ones save on a real traversal? And enumerating
 * the store walks the whole object directory - so what does that cost, and is it
 * the walk or the per-file work that dominates?
 *
 * <p>The second question is asked before anything is changed about enumeration,
 * because the answer decides whether an index is worth the consistency problem it
 * would create.
 */
class ObjectAccessIT {

    private static final int COMMITS = 10_000;

    private static String ratio(long before, long after) {
        return after == 0 ? "immeasurable" : String.format("%.2fx", before / (double) after);
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    @DisplayName("object reads and store enumeration at scale")
    void objectAccess(@TempDir Path parent) throws IOException {
        System.out.println("\n=== Object access: cached reads and enumeration ===");

        ScaleFixtures.Fixture fixture = ScaleFixtures.repository(parent, "objects", "main");
        ObjectId[] tip = new ObjectId[1];
        ScaleFixtures.timed("setup: " + COMMITS + " commits",
                () -> tip[0] = ScaleFixtures.linearHistory(fixture, "main", COMMITS));
        long objects = fixture.repository().objects().count();
        ScaleFixtures.note("objects stored", objects);

        // ------------------------------------------------------- cached reads
        // Two shapes, because they behave oppositely and only reporting the
        // flattering one would be a lie.
        //
        // A full-history scan touches more distinct objects than any bounded
        // cache can hold, so a least-recently-used cache is thrashed by it: by
        // the time the walk ends it holds the tail, and the next walk starts at
        // the head that was just evicted. Every lookup misses and the bookkeeping
        // is pure cost.
        //
        // Re-reading a bounded set - what paging a log or drawing an insight over
        // recent history actually does - is the case a cache is for.
        System.out.println("  full-history scan, larger than the cache:");
        CommitGraph graph = new CommitGraph(fixture.repository().objects());
        long cold = ScaleFixtures.timed("    cold walk of " + COMMITS + " commits",
                () -> graph.bfs(tip[0]));

        CommitGraph second = new CommitGraph(fixture.repository().objects());
        long warm = ScaleFixtures.timed("    again, same store",
                () -> second.bfs(tip[0]));

        assertThat(second.bfs(tip[0]))
                .as("a second walk answers exactly what the first one did")
                .isEqualTo(graph.bfs(tip[0]));
        ScaleFixtures.note("    ratio", ratio(cold, warm));

        System.out.println("  repeated reads of a bounded set:");
        ObjectId recent = fixture.repository().reader()
                .resolve("main~" + (COMMITS - 1_000))
                .orElseThrow();
        CommitGraph third = new CommitGraph(fixture.repository().objects());
        List<ObjectId> window = third.bfs(recent);
        ScaleFixtures.note("    objects in the window", window.size());

        // A fresh store over the same files, so the window is genuinely cold for
        // it while the repository itself is untouched.
        var coldStore = new com.gitforge.vcs.storage.FileSystemObjectStore(
                fixture.root().resolve("repository"));
        long boundedCold = ScaleFixtures.timed("    cold read of the window", () -> {
            for (ObjectId id : window) {
                coldStore.read(id);
            }
        });
        long boundedWarm = ScaleFixtures.timed("    same window, four more times", () -> {
            for (int round = 0; round < 4; round++) {
                for (ObjectId id : window) {
                    coldStore.read(id);
                }
            }
        });
        ScaleFixtures.note("    ratio per pass", ratio(boundedCold, boundedWarm / 4));

        // ------------------------------------------------------- enumeration
        System.out.println("  enumeration:");
        long[] counted = new long[1];
        ScaleFixtures.timed("  count()",
                () -> counted[0] = fixture.repository().objects().count());
        List<ObjectId>[] listed = new List[1];
        ScaleFixtures.timed("  listIds()",
                () -> listed[0] = fixture.repository().objects().listIds());
        ScaleFixtures.timed("  count() again", () -> fixture.repository().objects().count());

        assertThat(counted[0]).isEqualTo(objects);
        assertThat(listed[0]).hasSize((int) objects);

        // How much of that is the directory walk, and how much is the per-file
        // work the walk does? Measured by walking the same tree without asking
        // anything about each entry.
        Path objectsRoot = fixture.root().resolve("repository").resolve("objects");
        long[] bare = new long[1];
        ScaleFixtures.timed("  bare directory walk, no per-file question", () -> {
            long seen = 0;
            try (var shards = Files.newDirectoryStream(objectsRoot)) {
                for (Path shard : shards) {
                    try (var files = Files.newDirectoryStream(shard)) {
                        for (Path ignored : files) {
                            seen++;
                        }
                    }
                }
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
            bare[0] = seen;
        });
        ScaleFixtures.note("  entries seen by the bare walk", bare[0]);

        // ------------------------------------------- enumeration after change
        ScaleFixtures.timed("  one more commit, then count()",
                () -> ScaleFixtures.linearHistory(fixture, "main", 1));
        long grown = fixture.repository().objects().count();
        ScaleFixtures.note("  objects after one more commit", grown);
        // One object, not three: the blob and tree of that revision are already
        // stored, and content addressing files identical bytes once.
        assertThat(grown)
                .as("a count taken after a write sees the write")
                .isEqualTo(objects + 1);

        ScaleFixtures.timed("  count() after collection", () -> fixture.repository().gc().collect());
        ScaleFixtures.note("  objects after collection", fixture.repository().objects().count());
    }
}
