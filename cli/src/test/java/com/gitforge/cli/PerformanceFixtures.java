package com.gitforge.cli;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.repository.FileChange;
import com.gitforge.vcs.repository.RepositoryId;
import com.gitforge.vcs.repository.VcsRepository;
import com.gitforge.vcs.repository.VcsRepositoryFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Repositories large enough to make a bad algorithm visible.
 *
 * <p>Built through the real engine — {@code CommitService}, the real object
 * store, the real reference store — rather than by writing files that look like
 * a repository. A fixture assembled by hand would be a fixture whose shape the
 * code under test never actually produces, and the measurement would be of
 * something nobody runs.
 *
 * <p>Sizes are the ones the gate names and are not reduced. They are slow to
 * build, which is why building is timed separately from the operations: setup
 * cost is a property of the fixture, not of the command being measured.
 */
final class PerformanceFixtures {

    private PerformanceFixtures() {
    }

    static final Signature AUTHOR =
            Signature.of("perf", "perf@localhost", Instant.parse("2026-01-01T00:00:00Z"));

    /** A repository laid out the way the CLI lays one out. */
    record Fixture(Path treeRoot, VcsRepository repository) {
    }

    /**
     * An empty repository in the CLI's own layout.
     *
     * <p>Deliberately mirrors {@code Workspace}: the working tree holds a
     * {@code .gitforge} directory and the repository lives beneath it, so the
     * binary can be pointed at the result.
     */
    static Fixture emptyRepository(Path treeRoot, String defaultBranch) throws IOException {
        Path metadata = treeRoot.resolve(".gitforge");
        Files.createDirectories(metadata);
        VcsRepositoryFactory factory = new VcsRepositoryFactory(metadata);
        VcsRepository repository =
                factory.initialise(RepositoryId.of("repository"), defaultBranch);
        return new Fixture(treeRoot, repository);
    }

    /**
     * A linear history of the requested depth.
     *
     * <p>One file changed per commit, so every commit produces a new blob, a new
     * tree and a new commit object — three objects each, which is what makes the
     * object count predictable from the commit count.
     *
     * @return the tip commit
     */
    static ObjectId linearHistory(Fixture fixture, String branch, int commits) {
        ObjectId tip = null;
        for (int i = 0; i < commits; i++) {
            List<FileChange> changes = List.of(new FileChange.Put(
                    "file.txt",
                    ("revision " + i + "\n").getBytes(StandardCharsets.UTF_8),
                    FileMode.REGULAR_FILE));
            tip = fixture.repository().commits().commit(branch, changes, AUTHOR, "Commit " + i);
        }
        return tip;
    }

    /**
     * A history broad enough to reach an object count rather than a commit count.
     *
     * <p>Each commit writes {@code filesPerCommit} distinct files, so the store
     * grows by roughly that many blobs plus a tree plus a commit. Reaching twenty
     * thousand objects this way takes far fewer commits than one file at a time,
     * which keeps the fixture buildable while still exercising enumeration.
     */
    static ObjectId broadHistory(Fixture fixture, String branch, int commits, int filesPerCommit) {
        ObjectId tip = null;
        for (int i = 0; i < commits; i++) {
            List<FileChange> changes = new java.util.ArrayList<>(filesPerCommit);
            for (int f = 0; f < filesPerCommit; f++) {
                changes.add(new FileChange.Put(
                        "dir" + (f % 16) + "/file" + f + ".txt",
                        ("commit " + i + " file " + f + "\n").getBytes(StandardCharsets.UTF_8),
                        FileMode.REGULAR_FILE));
            }
            tip = fixture.repository().commits().commit(branch, changes, AUTHOR, "Batch " + i);
        }
        return tip;
    }

    /** Creates many branches, all at one commit. */
    static void manyBranches(Fixture fixture, ObjectId at, int count, String prefix) {
        for (int i = 0; i < count; i++) {
            fixture.repository().branches().createBranch(prefix + i, at);
        }
    }

    /** Creates many lightweight tags, all at one commit. */
    static void manyTags(Fixture fixture, ObjectId at, int count, String prefix) {
        for (int i = 0; i < count; i++) {
            fixture.repository().tags().createLightweight(prefix + i, at);
        }
    }

    /** A directory chain of the requested depth, returning the deepest directory. */
    static Path deepTree(Path root, int depth) throws IOException {
        Path current = root;
        for (int i = 0; i < depth; i++) {
            current = current.resolve("level" + i);
        }
        Files.createDirectories(current);
        return current;
    }

    /** How many objects the store holds. */
    static long objectCount(Fixture fixture) {
        return fixture.repository().objects().count();
    }
}
