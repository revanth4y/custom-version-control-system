package com.gitforge.cli;

/**
 * What the shell learns when a command ends.
 *
 * <p>A script cannot read prose, so the exit status is the only part of the
 * output that is guaranteed to be understood. These values are therefore part
 * of the interface and change only with the major version: a pipeline that
 * branches on {@code 4} today must still mean "forbidden" tomorrow.
 *
 * <p>The vocabulary is deliberately the server's. Each code corresponds to an
 * {@code ApiError} code the HTTP layer already returns, so a failure means the
 * same thing whether it was decided locally or remotely, and there is one table
 * to learn rather than two.
 */
public enum ExitCode {

    /** The command did what it was asked. */
    SUCCESS(0),

    /** Something failed that none of the more specific codes describes. */
    FAILURE(1),

    /** The command line itself was wrong: unknown flag, missing argument, bad value. */
    USAGE(2),

    /** The named repository, ref, object, issue or release does not exist — or is not visible. */
    NOT_FOUND(3),

    /** It exists and the caller may not do this to it. */
    FORBIDDEN(4),

    /** Refused rather than failed: read-only mode, or a destructive act without consent. */
    REFUSED(5),

    /** A path or operation tried to leave the sandbox. */
    SANDBOX_VIOLATION(6),

    /** The operation cannot apply to the current state: non-fast-forward, merge conflict, duplicate name. */
    CONFLICT(7),

    /** Objects could not be moved to or from a remote. */
    REMOTE_TRANSFER(8),

    /** Verification found damage, or could not complete enough of a check to answer. */
    VERIFICATION_FAILED(9),

    /** The command ran out of the time it was given. */
    TIMEOUT(10);

    private final int number;

    ExitCode(int number) {
        this.number = number;
    }

    public int number() {
        return number;
    }
}
