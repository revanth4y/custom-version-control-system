package com.gitforge.vcs.diff;

/**
 * One line in a hunk.
 *
 * <p>Line numbers are 1-based and null on the side where the line does not
 * exist: an added line has no old number, a removed line has no new one. That
 * makes the gutter renderable directly, with no arithmetic at the edges.
 */
public record DiffLine(Type type, Integer oldNumber, Integer newNumber, String content) {

    public enum Type {
        /** Unchanged, shown for orientation around a change. */
        CONTEXT,
        ADDED,
        REMOVED
    }

    public static DiffLine context(int oldNumber, int newNumber, String content) {
        return new DiffLine(Type.CONTEXT, oldNumber, newNumber, content);
    }

    public static DiffLine added(int newNumber, String content) {
        return new DiffLine(Type.ADDED, null, newNumber, content);
    }

    public static DiffLine removed(int oldNumber, String content) {
        return new DiffLine(Type.REMOVED, oldNumber, null, content);
    }
}
