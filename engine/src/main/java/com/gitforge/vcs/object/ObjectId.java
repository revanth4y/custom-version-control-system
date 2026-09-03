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

    /**
     * The shortest abbreviation that is worth resolving.
     *
     * <p>Four is Git's own floor, and the reasoning carries over unchanged: below
     * it a prefix collides so readily that the answer would more often be a
     * refusal than a commit, and a single hex character would match roughly one
     * object in sixteen.
     */
    public static final int MIN_PREFIX_LENGTH = 4;

    /**
     * Whether {@code candidate} could be the leading characters of an object id.
     *
     * <p>Deliberately not a parse: a prefix is not an id and has no bytes of its
     * own, so there is nothing to construct. What a caller needs to know is
     * whether looking it up in the store is worth doing, and that is a question
     * about the string.
     *
     * <p>A full forty characters is a valid prefix of itself. Callers that have
     * already tried an exact match simply never reach here with one.
     */
    public static boolean isValidPrefix(String candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate.length() < MIN_PREFIX_LENGTH || candidate.length() > LENGTH * 2) {
            return false;
        }
        return candidate.chars().allMatch(ObjectId::isHexDigit);
    }

    /**
     * A prefix in the form the store files objects under.
     *
     * <p>Ids are written lower-case, so an upper-case prefix would match nothing
     * at all — which reads as "no such object" when the truth is that the caller
     * typed the same id in a different case. Normalising here keeps that
     * distinction from ever arising.
     *
     * @throws IllegalArgumentException if {@code prefix} is not a valid prefix
     */
    public static String normalisePrefix(String prefix) {
        if (!isValidPrefix(prefix)) {
            throw new IllegalArgumentException(
                    "Object id prefix must be " + MIN_PREFIX_LENGTH + " to " + (LENGTH * 2)
                            + " hexadecimal characters, got: " + prefix);
        }
        return prefix.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
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
