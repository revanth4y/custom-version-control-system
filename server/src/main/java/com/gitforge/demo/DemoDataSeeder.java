package com.gitforge.demo;

import com.gitforge.issue.IssueCommentRepository;
import com.gitforge.issue.IssueRepository;
import com.gitforge.repo.RepoRepository;
import com.gitforge.user.UserRepository;
import com.gitforge.vcsapi.VcsStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Rebuilds the demonstration dataset from nothing.
 *
 * <p><strong>This deletes everything.</strong> Every account, repository, issue
 * and comment in the database, and every object and reference under the storage
 * root. It exists so the demo environment has one known state that can be
 * returned to, rather than accumulating whatever was left behind by the last
 * person poking at it.
 *
 * <p>Two independent gates stand between it and a real deployment, and both must
 * be open: the {@code demo} Spring profile has to be active, and
 * {@code gitforge.demo.reset} has to be true. Production Compose sets neither.
 * Neither alone is enough - a stray property in an environment file cannot arm
 * it, and running with the profile does not by itself destroy anything. The
 * profile is checked again here at runtime rather than trusted from the
 * annotation, because the cost of being wrong is the whole database.
 *
 * <p>Idempotent by construction: it does not reconcile or merge, it wipes and
 * rebuilds, so running it ten times leaves exactly what running it once leaves.
 */
@Component
@Profile(DemoDataSeeder.PROFILE)
@ConditionalOnProperty(prefix = "gitforge.demo", name = "reset", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    /** The only profile under which any of this may run. */
    public static final String PROFILE = "demo";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final Environment environment;
    private final DemoDataset dataset;
    private final DemoProperties properties;
    private final VcsStorageProperties storage;

    private final IssueCommentRepository comments;
    private final IssueRepository issues;
    private final RepoRepository repos;
    private final UserRepository users;

    public DemoDataSeeder(
            Environment environment,
            DemoDataset dataset,
            DemoProperties properties,
            VcsStorageProperties storage,
            IssueCommentRepository comments,
            IssueRepository issues,
            RepoRepository repos,
            UserRepository users) {

        this.environment = environment;
        this.dataset = dataset;
        this.properties = properties;
        this.storage = storage;
        this.comments = comments;
        this.issues = issues;
        this.repos = repos;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        requireDemoProfile();

        Instant epoch = properties.epochOrNow();
        log.warn("Demo reset: deleting all accounts, repositories and stored objects");

        Instant startedAt = Instant.now();
        wipeDatabase();
        wipeStorage();
        dataset.seed(epoch);

        log.warn(
                "Demo reset complete in {} ms, dated from {}",
                Duration.between(startedAt, Instant.now()).toMillis(),
                epoch);
    }

    /**
     * The annotation should have guaranteed this. It is checked anyway: a
     * component scan misconfigured later, or this class being referenced
     * directly, would otherwise silently empty a live database.
     */
    private void requireDemoProfile() {
        boolean active = Arrays.asList(environment.getActiveProfiles()).contains(PROFILE);
        if (!active) {
            throw new IllegalStateException(
                    "Refusing to reset demo data: the '" + PROFILE + "' profile is not active");
        }
    }

    /** Children before parents, so no foreign key is left pointing at nothing. */
    private void wipeDatabase() {
        comments.deleteAllInBatch();
        issues.deleteAllInBatch();
        repos.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    /**
     * Empties the storage root without removing it.
     *
     * <p>The directory itself is often a mount point, which cannot be deleted
     * and recreated from inside the container.
     */
    private void wipeStorage() {
        Path root = Path.of(storage.root());
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            // Deepest first, so a directory is empty by the time it is removed.
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) {
                    Files.delete(path);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not clear demo storage at " + root, ex);
        }
    }
}
