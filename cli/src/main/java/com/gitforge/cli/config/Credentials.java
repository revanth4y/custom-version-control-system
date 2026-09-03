package com.gitforge.cli.config;

import com.gitforge.cli.CliException;
import com.gitforge.cli.output.Json;
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
 * <p>The file is owner-only where the filesystem can express it, and the token
 * is registered with the {@link Redactor} the moment it is read, so it is masked
 * everywhere before any command has had a chance to print it.
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
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
            restrict(file);
        } catch (IOException unwritable) {
            throw CliException.failure("Could not write " + file + ": " + unwritable.getMessage());
        }
    }

    /** The permissions as they actually are, for {@code auth status} to report. */
    public String permissions() {
        try {
            return java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(file));
        } catch (IOException | UnsupportedOperationException notPosix) {
            return "unavailable";
        }
    }

    private static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(
                    path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException notPosix) {
            // Windows has no POSIX bits. Reported by permissions() rather than
            // pretended about.
        }
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
