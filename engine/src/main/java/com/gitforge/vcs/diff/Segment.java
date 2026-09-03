package com.gitforge.vcs.diff;

/**
 * A changed run of characters within one diff line.
 *
 * <p>Half-open: {@code start} is included, {@code end} is not, both measured in
 * the same units as {@link String#length()} — UTF-16 code units. That choice is
 * deliberate rather than incidental: JavaScript strings are also UTF-16, so an
 * offset computed here indexes the browser's copy of the same line without any
 * conversion. Using code points instead would be defensible in isolation and
 * wrong at the boundary, because the client would have to re-scan every line to
 * translate them back.
 *
 * @param start first changed position, inclusive
 * @param end one past the last changed position
 */
public record Segment(int start, int end) {

    public Segment {
        if (start < 0) {
            throw new IllegalArgumentException("Segment start must not be negative: " + start);
        }
        if (end <= start) {
            throw new IllegalArgumentException("Segment must not be empty: [" + start + ", " + end + ")");
        }
    }

    /** How many characters the run covers. */
    public int length() {
        return end - start;
    }
}
