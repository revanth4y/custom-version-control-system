package com.gitforge.vcs.diff;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;

import java.util.List;

/**
 * Everything known about how one file changed.
 *
 * <p>Carries the structural facts — which blobs, which modes, added or removed —
 * alongside the line-level hunks, so a caller has both the summary and the
 * detail from a single result.
 *
 * @param binary true when the content cannot be treated as text, in which case
 *     there are no hunks because a line diff would be meaningless
 * @param tooLarge true when the file was skipped to bound the work; also leaves
 *     hunks empty, and is deliberately distinct from binary so a client can
 *     explain which reason applies
 * @param oldSize bytes on the old side, 0 when the file did not exist
 * @param newSize bytes on the new side, 0 when the file was deleted. Carried
 *     because it is the only measure of a change whose content cannot be shown
 *     as lines, and the blobs are already in hand when the diff is computed -
 *     a caller asking afterwards would have to fetch entire files to count them
 */
public record FileDiff(
        String path,
        Status status,
        ObjectId oldBlob,
        ObjectId newBlob,
        FileMode oldMode,
        FileMode newMode,
        boolean binary,
        boolean tooLarge,
        List<Hunk> hunks,
        int additions,
        int deletions,
        int oldSize,
        int newSize) {

    public enum Status {
        ADDED,
        DELETED,
        MODIFIED
    }

    public FileDiff {
        hunks = List.copyOf(hunks);
    }

    public boolean hasHunks() {
        return !hunks.isEmpty();
    }
}
