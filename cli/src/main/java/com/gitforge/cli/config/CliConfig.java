package com.gitforge.cli.config;

import com.gitforge.cli.CliException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Settings that outlive one command.
 *
 * <p>A flat key-value file, sorted on write, so that two machines with the same
 * settings have byte-identical files and a diff of one means something.
 *
 * <p>The keys are a closed set. An open one reads better in a demo and is worse
 * in practice: a typo in {@code api.url} becomes a setting that is silently
 * ignored, and the failure surfaces much later as "it is talking to the wrong
 * server". Refusing an unknown key costs a moment now and saves that.
 *
 * <p>Credentials are not here. They live in their own file with their own
 * permissions, because a settings file is something people paste into issues.
 */
public final class CliConfig {

    /** Every key the CLI understands, with what it means. */
    public static final Map<String, String> KNOWN = Map.of(
            "api.url", "Base URL of the GitForge API, such as http://localhost:8080/api/v1",
            "sandbox.root", "Directory the CLI is confined to",
            "output.json", "Default every command to --json (true/false)",
            "output.color", "Allow colour when the output is a terminal (true/false)");

    private final Path file;
    private final Map<String, String> values = new TreeMap<>();

    public CliConfig(Path file) {
        this.file = file;
        load();
    }

    public Path file() {
        return file;
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(requireKnown(key)));
    }

    public String getOrDefault(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    public void set(String key, String value) {
        values.put(requireKnown(key), value);
        save();
    }

    public boolean unset(String key) {
        boolean had = values.remove(requireKnown(key)) != null;
        save();
        return had;
    }

    /** Every set value, sorted. */
    public Map<String, String> all() {
        return new LinkedHashMap<>(values);
    }

    /** Every key the CLI understands, whether set or not. */
    public static Set<String> knownKeys() {
        return new TreeMap<>(KNOWN).keySet();
    }

    private static String requireKnown(String key) {
        if (key == null || !KNOWN.containsKey(key)) {
            throw CliException.usage(
                    "Unknown setting '" + key + "'. Known settings: "
                            + String.join(", ", new TreeMap<>(KNOWN).keySet()));
        }
        return key;
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int split = trimmed.indexOf('=');
                if (split > 0) {
                    String key = trimmed.substring(0, split).trim();
                    // Unknown keys in an existing file are kept out of the map
                    // rather than refused: a settings file written by a newer
                    // version should not stop an older one from running.
                    if (KNOWN.containsKey(key)) {
                        values.put(key, trimmed.substring(split + 1).trim());
                    }
                }
            }
        } catch (IOException unreadable) {
            throw CliException.failure("Could not read " + file + ": " + unreadable.getMessage());
        }
    }

    private void save() {
        StringBuilder body = new StringBuilder("# GitForge CLI settings.\n");
        values.forEach((key, value) -> body.append(key).append('=').append(value).append('\n'));
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            throw CliException.failure("Could not write " + file + ": " + unwritable.getMessage());
        }
    }
}
