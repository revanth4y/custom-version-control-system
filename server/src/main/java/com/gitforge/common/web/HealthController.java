package com.gitforge.common.web;

import com.gitforge.vcsapi.VcsStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Whether the server can actually do its job.
 *
 * <p>Readiness rather than liveness. Answering "UP" merely because the JVM is
 * running would make the container healthy while every request failed, and a
 * Compose {@code depends_on: service_healthy} built on that promises nothing. So
 * both dependencies are checked: the database it reads from, and the storage it
 * writes objects to.
 *
 * <p>Deliberately not Actuator. One endpoint that checks these two things is
 * less to configure, less to secure and less to explain than a dependency whose
 * default surface is much wider than this.
 *
 * <p>Unauthenticated, because the thing polling it is Docker, which holds no
 * credentials. It reveals only whether two subsystems answered - no versions, no
 * paths, no connection details.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /** Long enough for a loaded pool, short enough not to hold up a healthcheck. */
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private final DataSource dataSource;
    private final Path storageRoot;

    public HealthController(DataSource dataSource, VcsStorageProperties storage) {
        this.dataSource = dataSource;
        this.storageRoot = Path.of(storage.root());
    }

    /** @param status UP only when every dependency is UP */
    public record Health(String status, String database, String storage) {
    }

    @GetMapping
    public ResponseEntity<Health> health() {
        String database = databaseStatus();
        String storage = storageStatus();

        boolean healthy = UP.equals(database) && UP.equals(storage);
        Health body = new Health(healthy ? UP : DOWN, database, storage);

        // 503 rather than a 200 carrying bad news: an orchestrator reads the
        // status code, not the payload.
        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private String databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS) ? UP : DOWN;
        } catch (SQLException ex) {
            log.warn("Health check: database unavailable", ex);
            return DOWN;
        }
    }

    /**
     * Writable, not merely present.
     *
     * <p>A read-only or full volume is the failure worth catching: the directory
     * is still there and still listable, and every commit fails anyway.
     */
    private String storageStatus() {
        try {
            if (!Files.isDirectory(storageRoot)) {
                log.warn("Health check: storage root {} is not a directory", storageRoot);
                return DOWN;
            }
            Path probe = Files.createTempFile(storageRoot, ".health-", ".tmp");
            Files.delete(probe);
            return UP;
        } catch (IOException ex) {
            log.warn("Health check: storage root is not writable", ex);
            return DOWN;
        }
    }
}
