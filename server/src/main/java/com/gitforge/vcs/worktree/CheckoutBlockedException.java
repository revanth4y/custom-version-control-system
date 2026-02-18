package com.gitforge.vcs.worktree;

/**
 * A checkout was refused because completing it would have destroyed work.
 *
 * <p>Carries the offending paths so a caller can report precisely what is in the
 * way rather than telling the user only that something is.
 */
public class CheckoutBlockedException extends RuntimeException {

    private final transient WorkingTreeStatus status;

    public CheckoutBlockedException(String message, WorkingTreeStatus status) {
        super(message);
        this.status = status;
    }

    public WorkingTreeStatus status() {
        return status;
    }
}
