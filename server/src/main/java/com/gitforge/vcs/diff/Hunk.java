package com.gitforge.vcs.diff;

import java.util.List;

/**
 * A contiguous run of changes with its surrounding context.
 *
 * <p>Counts and starts describe the region each side covers, in the same terms a
 * unified diff header uses, so a renderer needs no further arithmetic.
 *
 * @param oldStart 1-based first line on the old side, or 0 when the old side is empty
 * @param newStart 1-based first line on the new side, or 0 when the new side is empty
 */
public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<DiffLine> lines) {

    public Hunk {
        lines = List.copyOf(lines);
    }

    /** The {@code @@ -a,b +c,d @@} header of a unified diff. */
    public String header() {
        return "@@ -" + oldStart + "," + oldCount + " +" + newStart + "," + newCount + " @@";
    }
}
