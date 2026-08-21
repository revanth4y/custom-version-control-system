package com.gitforge.vcs.object;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The kinds of object the store holds.
 *
 * <p>The {@link #header()} text is part of the serialized representation and
 * therefore contributes to every object's identity: renaming one of these
 * literals would change every hash in every repository.
 */
public enum ObjectType {

    BLOB("blob"),
    TREE("tree"),
    COMMIT("commit");

    private final String header;

    ObjectType(String header) {
        this.header = header;
    }

    /** The literal written into the object header, such as {@code blob}. */
    public String header() {
        return header;
    }

    public byte[] headerBytes() {
        return header.getBytes(StandardCharsets.US_ASCII);
    }

    public static ObjectType fromHeader(String header) {
        return Arrays.stream(values())
                .filter(type -> type.header.equals(header))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown object type: " + header));
    }
}
