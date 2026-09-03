package com.gitforge.vcs.object;

import java.nio.charset.StandardCharsets;

/**
 * The mode recorded against a tree entry.
 *
 * <p>The stored text matters byte for byte: it is written verbatim into the tree
 * payload and so feeds directly into the tree's hash. Note that directories are
 * stored as {@code 40000}, without a leading zero, even though the value is
 * conventionally written as {@code 040000} in octal.
 */
public enum FileMode {

    REGULAR_FILE("100644"),
    EXECUTABLE_FILE("100755"),
    DIRECTORY("40000");

    private final String value;

    FileMode(String value) {
        this.value = value;
    }

    /** The exact characters written into a tree entry. */
    public String value() {
        return value;
    }

    public byte[] valueBytes() {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    public boolean isDirectory() {
        return this == DIRECTORY;
    }

    /**
     * Parses a mode from a tree entry.
     *
     * <p>A leading zero on the directory mode is accepted on input, since some
     * tooling writes {@code 040000}, but {@link #value()} is always the
     * canonical form so that output stays byte-exact.
     */
    public static FileMode fromValue(String value) {
        for (FileMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        if ("040000".equals(value)) {
            return DIRECTORY;
        }
        throw new IllegalArgumentException("Unsupported file mode: " + value);
    }
}
