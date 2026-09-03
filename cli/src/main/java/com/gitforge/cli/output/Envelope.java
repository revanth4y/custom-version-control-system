package com.gitforge.cli.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of every machine-readable answer.
 *
 * <p>One envelope for all commands, so a script can tell success from failure
 * without knowing which command it ran. {@code ok} is the only field it has to
 * look at first, and {@code error.code} is the only field it needs to branch on
 * after that.
 *
 * <p>{@code schemaVersion} is here so the shape can change one day without
 * silently breaking every consumer: a reader that checks it will know, and a
 * reader that ignores it made that choice explicitly.
 *
 * <p>Insertion-ordered maps throughout. Key order in JSON is not semantically
 * meaningful, but byte-identical output between two runs is what makes golden
 * files and {@code diff} usable as tests, and a hash map would scramble that
 * differently on every JVM.
 */
public final class Envelope {

    public static final int SCHEMA_VERSION = 1;

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private Envelope() {
    }

    /** A successful answer carrying data. */
    public static Envelope ok(String command, Object data, List<String> warnings) {
        Envelope envelope = new Envelope();
        envelope.fields.put("schemaVersion", SCHEMA_VERSION);
        envelope.fields.put("command", command);
        envelope.fields.put("ok", true);
        envelope.fields.put("data", data == null ? new LinkedHashMap<String, Object>() : data);
        envelope.fields.put("warnings", warnings == null ? List.of() : new ArrayList<>(warnings));
        return envelope;
    }

    /**
     * A failure.
     *
     * <p>Deliberately without {@code data}: a caller that has to guess whether a
     * field is present because the command half-worked will eventually guess
     * wrong. Failure carries a code and a message, and nothing else.
     */
    public static Envelope error(String command, String code, String message) {
        Envelope envelope = new Envelope();
        envelope.fields.put("schemaVersion", SCHEMA_VERSION);
        envelope.fields.put("command", command);
        envelope.fields.put("ok", false);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        envelope.fields.put("error", error);
        return envelope;
    }

    public Map<String, Object> fields() {
        return fields;
    }
}
