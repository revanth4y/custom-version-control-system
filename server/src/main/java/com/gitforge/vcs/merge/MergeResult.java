package com.gitforge.vcs.merge;

import com.gitforge.vcs.diff.TreeChange;
import com.gitforge.vcs.object.ObjectId;

import java.util.List;

/**
 * The outcome of merging three trees.
 *
 * <p>A conflicted merge deliberately produces <em>no</em> tree. Emitting one
 * that silently picked a side at every conflicting path would be a result a
 * caller could commit by accident, believing the merge had succeeded. A merged
 * tree exists only once there is genuinely nothing left to resolve.
 */
public sealed interface MergeResult permits MergeResult.Clean, MergeResult.Conflicted {

    boolean isClean();

    /** Every side merged without disagreement; {@code tree} is the merged state. */
    record Clean(ObjectId tree) implements MergeResult {

        public Clean {
            if (tree == null) {
                throw new IllegalArgumentException("A clean merge must produce a tree");
            }
        }

        @Override
        public boolean isClean() {
            return true;
        }
    }

    /**
     * At least one path could not be resolved.
     *
     * @param conflicts unresolved paths, sorted by path
     * @param cleanlyMerged changes that would be applied to <em>ours</em> for the
     *     paths that did resolve, so a caller can show what the merge achieved
     *     alongside what it could not
     */
    record Conflicted(List<MergeConflict> conflicts, List<TreeChange> cleanlyMerged) implements MergeResult {

        public Conflicted {
            if (conflicts == null || conflicts.isEmpty()) {
                throw new IllegalArgumentException("A conflicted merge must report at least one conflict");
            }
            conflicts = List.copyOf(conflicts);
            cleanlyMerged = List.copyOf(cleanlyMerged);
        }

        @Override
        public boolean isClean() {
            return false;
        }
    }
}
