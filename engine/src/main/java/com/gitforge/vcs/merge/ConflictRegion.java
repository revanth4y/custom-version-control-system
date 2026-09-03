package com.gitforge.vcs.merge;

/**
 * One stretch of a file the two sides could not be reconciled over.
 *
 * <p>A content conflict is rarely about the whole file. When a line-level merge
 * resolves most of a file and fails on part of it, this says which part: the
 * lines involved in the base, in ours, and in theirs. All three are carried
 * because all three take part in the decision — the base is what makes a change
 * a change, and without it the reader cannot tell an edit from an insertion.
 *
 * <p>Ranges refer to the three files as they are, not to any merged result:
 * there is no merged result for a conflicted region, which is the point.
 */
public record ConflictRegion(LineRange base, LineRange ours, LineRange theirs) {

    public ConflictRegion {
        if (base == null || ours == null || theirs == null) {
            throw new IllegalArgumentException("A conflict region needs a range on all three sides");
        }
    }
}
