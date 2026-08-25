package com.gitforge.vcsapi;

/**
 * How much of a file this API will carry, in either direction.
 *
 * <p>One value with one home. The same number has to bound writing a file and
 * reading it back, and holding it in two places is how a repository ends up
 * containing something it will not return: the write side accepts what the read
 * side then refuses, and the file becomes unreachable through the only endpoint
 * that serves it.
 */
final class ContentLimits {

    /**
     * Ten megabytes for a single file.
     *
     * <p>Holds any source file, image or document someone commits through a web
     * interface, and twenty times the largest object this engine has been asked
     * to store.
     *
     * <p>Enforced on every path that writes a file and on the one that reads one,
     * so what the API accepts is exactly what it will give back.
     */
    static final int MAX_BLOB_BYTES = 10 * 1024 * 1024;

    private ContentLimits() {
    }

    /** Whether {@code bytes} is small enough to store or serve. */
    static boolean withinBlobLimit(long bytes) {
        return bytes <= MAX_BLOB_BYTES;
    }
}
