package com.gitforge.vcs.merge;

/**
 * A run of lines in one version of a file.
 *
 * <p>Half-open and one-based: {@code [start, end)} covers lines {@code start}
 * up to but not including {@code end}, numbered as a person reads them. The
 * half-open form is what lets an empty range say something rather than nothing
 * — a side that contributes no lines to a conflicting region has
 * {@code start == end}, which is exactly how "we deleted this" differs from "we
 * left one line here".
 *
 * @param start first line of the run, one-based
 * @param end one past the last line
 */
public record LineRange(int start, int end) {

    public LineRange {
        if (start < 1) {
            throw new IllegalArgumentException("Line numbers start at 1: " + start);
        }
        if (end < start) {
            throw new IllegalArgumentException("A line range must not run backwards: [" + start + ", " + end + ")");
        }
    }

    /** How many lines the run covers; zero for a side that has none here. */
    public int length() {
        return end - start;
    }

    public boolean isEmpty() {
        return end == start;
    }
}
