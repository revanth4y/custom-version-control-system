package com.gitforge.vcs.hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The single place SHA-1 is computed.
 *
 * <p>Object identity in this system is defined as the SHA-1 of an object's
 * canonical uncompressed representation, so confining the algorithm to one class
 * keeps that definition unambiguous.
 *
 * <p>SHA-1 is used because it is the identity function of the object model being
 * implemented, not as a security primitive. It is not used for signatures,
 * passwords, or any other trust decision, so its collision weaknesses do not
 * apply here.
 */
public final class Sha1 {

    public static final int HASH_LENGTH = 20;

    private static final String ALGORITHM = "SHA-1";

    private Sha1() {
    }

    /** Returns the 20-byte SHA-1 digest of {@code data}. */
    public static byte[] hash(byte[] data) {
        return newDigest().digest(data);
    }

    /**
     * A fresh {@link MessageDigest}. Instances are stateful and not thread-safe,
     * so one is created per use rather than shared.
     */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-1 is required of every conforming Java platform.
            throw new IllegalStateException(ALGORITHM + " is not available on this JVM", ex);
        }
    }
}
