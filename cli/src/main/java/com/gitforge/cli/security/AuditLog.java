package com.gitforge.cli.security;

import com.gitforge.cli.output.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What was run, and what it did.
 *
 * <p>Append-only JSONL: one object per line, never rewritten. A log that can be
 * edited in place is a log that can be edited by whatever went wrong, and the
 * format is chosen so that a truncated write costs one line rather than the
 * file.
 *
 * <p>Every line passes through the {@link Redactor} on its way to disk, not on
 * its way out of it. Redacting at read time would mean the secret was on disk
 * the whole time, which is the thing being avoided.
 *
 * <p>Failing to write the log never fails the command. The alternative — a tool
 * that refuses to work because it cannot record that it worked — turns an
 * auditing convenience into an outage, and the audit trail is not the product.
 * A failure is reported once through the trace channel and then dropped.
 */
public final class AuditLog {

    private final Path file;
    private final Redactor redactor;
    private final List<String> problems = new ArrayList<>();

    public AuditLog(Path file, Redactor redactor) {
        this.file = file;
        this.redactor = redactor;
    }

    /**
     * Records one command.
     *
     * @param command the command path, such as {@code branch delete}
     * @param arguments the arguments as given, redacted before writing
     * @param decision the authorization outcome, where one was made
     * @param refsMoved references this command moved, empty when none
     * @param exitCode the process exit status
     */
    public void record(
            String command,
            List<String> arguments,
            String decision,
            List<String> refsMoved,
            int exitCode) {

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Json.time(Instant.now()));
        entry.put("command", command);
        entry.put("arguments", arguments == null ? List.of() : arguments);
        entry.put("decision", decision);
        entry.put("refsMoved", refsMoved == null ? List.of() : refsMoved);
        entry.put("exitCode", exitCode);

        String line = redactor.scrub(Json.write(entry));
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    file,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            restrict(file);
        } catch (IOException unwritable) {
            problems.add("Could not write the audit log at " + file + ": " + unwritable.getMessage());
        }
    }

    /** Anything that went wrong writing, for the trace channel. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /**
     * Owner-only, where the filesystem understands that.
     *
     * <p>The log records which repositories were touched and when, which is not
     * a secret but is nobody else's business either.
     */
    private static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(
                    path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException notPosix) {
            // Windows and some filesystems have no POSIX bits. Nothing to do,
            // and refusing to log on that basis would help nobody.
        }
    }
}
