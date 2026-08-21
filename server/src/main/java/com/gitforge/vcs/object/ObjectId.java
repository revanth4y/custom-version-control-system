package com.gitforge.vcs.object;

import com.gitforge.vcs.hash.Sha1;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * The identity of a stored object: a 20-byte SHA-1 digest.
 *
 * <p>Held as raw bytes rather than a hex string. That halves the memory per id
 * and, more importantly, makes this a proper value type for the hash-based
 * collections the graph traversals rely on.
 *
 * <p>Instances are immutable; every array crossing the boundary is copied.
 */
public final class ObjectId implements Comparable<ObjectId> {

    public static final int LENGTH = Sha1.HASH_LENGTH;

    private static final HexFormat HEX = HexFormat.of();

    private final byte[] bytes;

    private ObjectId(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Wraps a 20-byte digest, copying it so later mutation cannot alter this id. */
    public static ObjectId fromBytes(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Object id bytes must not be null");
        }
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException(
                    "Object id must be " + LENGTH + " bytes, got " + bytes.length);
        }
        return new ObjectId(bytes.clone());
    }

    /** Parses a 40-character hexadecimal digest. */
    public static ObjectId fromHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("Object id hex must not be null");
        }
        if (hex.length() != LENGTH * 2) {
            throw new IllegalArgumentException(
                    "Object id hex must be " + (LENGTH * 2) + " characters, got " + hex.length());
        }
        byte[] parsed;
        try {
            parsed = HEX.parseHex(hex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Object id hex contains non-hexadecimal characters: " + hex, ex);
        }
        return new ObjectId(parsed);
    }

    /** The digest of {@code content}, which must already be a canonical object representation. */
    public static ObjectId ofContent(byte[] content) {
        return new ObjectId(Sha1.hash(content));
    }

    public byte[] toBytes() {
        return bytes.clone();
    }

    public String toHex() {
        return HEX.formatHex(bytes);
    }

    /** The leading {@code length} hex characters, as used for display. */
    public String abbreviate(int length) {
        if (length < 1 || length > LENGTH * 2) {
            throw new IllegalArgumentException(
                    "Abbreviation length must be between 1 and " + (LENGTH * 2) + ", got " + length);
        }
        return toHex().substring(0, length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ObjectId id && Arrays.equals(bytes, id.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** Orders by unsigned byte value, matching the lexicographic order of the hex form. */
    @Override
    public int compareTo(ObjectId other) {
        return Arrays.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public String toString() {
        return toHex();
    }
}
