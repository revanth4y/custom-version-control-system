package com.gitforge.vcs.worktree;

/** The working tree could not be read or written. */
public class WorkingTreeException extends RuntimeException {

    public WorkingTreeException(String message) {
        super(message);
    }

    public WorkingTreeException(String message, Throwable cause) {
        super(message, cause);
    }
}
