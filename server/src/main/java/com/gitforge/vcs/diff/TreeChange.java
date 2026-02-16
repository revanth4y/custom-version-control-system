package com.gitforge.vcs.diff;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.ObjectId;

/**
 * One file-level difference between two trees.
 *
 * <p>Paths always name files, never directories. An added or deleted directory
 * is reported as its constituent files, which is what a diff means to a reader:
 * these are the files that changed. It also removes the need for a separate
 * "type changed" case — a path that was a file and became a directory is simply
 * a deletion plus the additions beneath it.
 */
public sealed interface TreeChange
        permits TreeChange.Added, TreeChange.Deleted, TreeChange.Modified {

    String path();

    /** A file present in the new tree and absent from the old one. */
    record Added(String path, FileMode mode, ObjectId blob) implements TreeChange {
    }

    /** A file present in the old tree and absent from the new one. */
    record Deleted(String path, FileMode mode, ObjectId blob) implements TreeChange {
    }

    /**
     * A file present in both trees whose content, mode, or both have changed.
     *
     * <p>Both modes are carried because a mode-only change — the same bytes made
     * executable — is a real difference with identical blob ids.
     */
    record Modified(
            String path,
            FileMode oldMode,
            ObjectId oldBlob,
            FileMode newMode,
            ObjectId newBlob) implements TreeChange {

        public boolean isContentChange() {
            return !oldBlob.equals(newBlob);
        }

        public boolean isModeChange() {
            return oldMode != newMode;
        }
    }
}
