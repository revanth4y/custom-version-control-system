package com.gitforge.vcs.worktree;

import java.util.List;

/**
 * How the files on disk differ from the tree they were materialized from.
 *
 * <p>Paths are repository-relative and use forward slashes on every platform.
 *
 * @param modified tracked files whose contents no longer hash to the recorded blob
 * @param deleted tracked files no longer present on disk
 * @param untracked files present on disk that the tree does not describe
 */
public record WorkingTreeStatus(List<String> modified, List<String> deleted, List<String> untracked) {

    public WorkingTreeStatus {
        modified = List.copyOf(modified);
        deleted = List.copyOf(deleted);
        untracked = List.copyOf(untracked);
    }

    public static WorkingTreeStatus clean() {
        return new WorkingTreeStatus(List.of(), List.of(), List.of());
    }

    /**
     * Whether the working tree matches its tree exactly.
     *
     * <p>Untracked files count as unclean for reporting, but do not by
     * themselves block a checkout — only those that collide with an incoming
     * path do.
     */
    public boolean isClean() {
        return modified.isEmpty() && deleted.isEmpty() && untracked.isEmpty();
    }

    /** Whether anything tracked has been changed locally. */
    public boolean hasLocalChanges() {
        return !modified.isEmpty() || !deleted.isEmpty();
    }
}
