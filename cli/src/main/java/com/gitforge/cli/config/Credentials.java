package com.gitforge.cli.config;

import com.gitforge.cli.CliException;
import com.gitforge.cli.output.Json;
import com.gitforge.cli.security.OwnerOnlyFile;
import com.gitforge.cli.security.Redactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where the token lives.
 *
 * <p>A token and never a password. The CLI exchanges credentials for a token
 * once, at {@code auth login}, and forgets the password in the same breath —
 * there is no field to store one in and no code path that would write one. That
 * is a deliberate limit on the blast radius: a stolen credentials file yields
 * something that expires, not something that unlocks an account.
 *
 * <p>The file is owner-only, and the CLI refuses to write a token where that
 * cannot be arranged - {@link OwnerOnlyFile} is where that is enforced, and
 * where the reasoning about Windows lives. The token is registered with the
 * {@link Redactor} the moment it is read, so it is masked everywhere before any
 * command has had a chance to print it.
 *
 * <p>Nothing here ever puts a token in a URL or in {@code argv}. A URL with
 * credentials ends up in shell history, in server logs and in error messages;
 * {@code argv} is readable by every other process on the machine.
 */
public final class Credentials {

    private final Path file;
    private final Redactor redactor;

    public Credentials(Path file, Redactor redactor) {
        this.file = file;
        this.redactor = redactor;
    }

    public Path file() {
        return file;
    }

    /** The stored token for a host, if there is one. */
    public Optional<String> tokenFor(String host) {
        Map<String, String> all = read();
        String token = all.get(host);
        if (token != null) {
            redactor.remember(token);
        }
        return Optional.ofNullable(token);
    }

    /** Stores a token for a host, replacing any previous one. */
    public void store(String host, String token) {
        if (token == null || token.isBlank()) {
            throw CliException.usage("Refusing to store an empty token");
        }
        Map<String, String> all = read();
        all.put(host, token);
        write(all);
        redactor.remember(token);
    }

    /** Removes the token for a host. True when there was one to remove. */
    public boolean clear(String host) {
        Map<String, String> all = read();
        boolean had = all.remove(host) != null;
        write(all);
        return had;
    }

    /** The hosts a token is held for. Never the tokens themselves. */
    public java.util.List<String> hosts() {
        return read().keySet().stream().sorted().toList();
    }

    private Map<String, String> read() {
        Map<String, String> all = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return all;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int split = trimmed.indexOf('\t');
                if (split > 0) {
                    all.put(trimmed.substring(0, split), trimmed.substring(split + 1));
                }
            }
        } catch (IOException unreadable) {
            throw CliException.failure("Could not read " + file + ": " + unreadable.getMessage());
        }
        return all;
    }

    private void write(Map<String, String> all) {
        StringBuilder body = new StringBuilder();
        body.append("# GitForge credentials. One host and token per line, tab separated.\n");
        body.append("# Tokens expire; this file holds no passwords.\n");
        all.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> body.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n'));

        // Created and locked down before anything is written into it. Writing
        // first and restricting afterwards - which is what this did - leaves the
        // token readable for as long as the two calls are apart, and the failure
        // was swallowed, so on a filesystem without POSIX bits it stayed readable.
        OwnerOnlyFile.createProtected(file);
        try {
            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            throw CliException.failure("Could not write " + file + ": " + unwritable.getMessage());
        }
        // Again, after the write: a filesystem that resets a security descriptor
        // when a file is truncated and rewritten would otherwise undo the above,
        // and the verification inside restrict() is what makes that a failure
        // rather than a silent regression.
        OwnerOnlyFile.restrict(file);
    }

    /**
     * How the file is actually protected, for {@code auth status} to report.
     *
     * <p>This used to answer "unavailable" wherever POSIX bits were absent, which
     * is what a Windows user saw beside a file four principals could read: a true
     * statement about POSIX standing in for a false impression about safety.
     */
    public String permissions() {
        return OwnerOnlyFile.describe(file);
    }

    /** A description for {@code auth status}: hosts and file state, never a token. */
    public Map<String, Object> describe() {
        return Json.map(
                "file", file.toString(),
                "exists", Files.isRegularFile(file),
                "permissions", permissions(),
                "hosts", hosts());
    }
}
