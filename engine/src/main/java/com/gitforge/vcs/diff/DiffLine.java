package com.gitforge.vcs.diff;

import java.util.List;

/**
 * One line in a hunk.
 *
 * <p>Line numbers are 1-based and null on the side where the line does not
 * exist: an added line has no old number, a removed line has no new one. That
 * makes the gutter renderable directly, with no arithmetic at the edges.
 *
 * @param segments the runs of characters that changed within this line, empty
 *     unless it was paired with its counterpart and the comparison was
 *     affordable. Always empty on a context line, which by definition did not
 *     change. Filled in after line diffing by {@link InlineDiffer}, never by
 *     {@link LineDiffer}, which decides only which lines differ.
 */
public record DiffLine(
        Type type, Integer oldNumber, Integer newNumber, String content, List<Segment> segments) {

    public DiffLine {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /**
     * A line with no intra-line detail.
     *
     * <p>Kept so line diffing can construct its result without knowing that
     * intra-line annotation exists: it runs first, and every line starts without
     * it.
     */
    public DiffLine(Type type, Integer oldNumber, Integer newNumber, String content) {
        this(type, oldNumber, newNumber, content, List.of());
    }

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

    /** The same line, carrying the runs that changed within it. */
    public DiffLine withSegments(List<Segment> segments) {
        return new DiffLine(type, oldNumber, newNumber, content, segments);
    }
}
