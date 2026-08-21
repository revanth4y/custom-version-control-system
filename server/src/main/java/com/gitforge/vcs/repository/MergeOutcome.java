package com.gitforge.vcs.repository;

import com.gitforge.vcs.diff.TreeChange;
import com.gitforge.vcs.merge.MergeConflict;
import com.gitforge.vcs.object.ObjectId;

import java.util.List;

/**
 * What merging one branch into another did.
 *
 * <p>Four genuinely different results, kept as distinct types rather than a
 * status flag with nullable fields: a fast-forward has no merge commit to
 * report, and a conflict has no commit at all, so a single shape would carry
 * fields that are meaningless in three cases out of four.
 */
public sealed interface MergeOutcome
        permits MergeOutcome.AlreadyUpToDate, MergeOutcome.FastForwarded,
                MergeOutcome.Merged, MergeOutcome.Conflicted {

    boolean isSuccessful();

    /**
     * Their branch is already contained in ours. Nothing was created and no
     * reference moved.
     */
    record AlreadyUpToDate(ObjectId head) implements MergeOutcome {

        @Override
        public boolean isSuccessful() {
            return true;
        }
    }

    /**
     * Our branch was strictly behind theirs, so it simply moved forward.
     *
     * <p>No merge commit is created: there is nothing to reconcile, and inventing
     * a commit would add a parent that says nothing about how the code came to
     * be.
     */
    record FastForwarded(ObjectId newHead) implements MergeOutcome {

        @Override
        public boolean isSuccessful() {
            return true;
        }
    }

    /** The branches diverged and were reconciled by a merge commit. */
    record Merged(ObjectId mergeCommit, ObjectId tree) implements MergeOutcome {

        @Override
        public boolean isSuccessful() {
            return true;
        }
    }

    /**
     * The branches diverged in ways that could not be reconciled.
     *
     * <p>Nothing was written and no reference moved: there is no merge commit,
     * no merged tree, and the branch still points where it did.
     */
    record Conflicted(List<MergeConflict> conflicts, List<TreeChange> cleanlyMerged) implements MergeOutcome {

        public Conflicted {
            conflicts = List.copyOf(conflicts);
            cleanlyMerged = List.copyOf(cleanlyMerged);
        }

        @Override
        public boolean isSuccessful() {
            return false;
        }
    }
}
