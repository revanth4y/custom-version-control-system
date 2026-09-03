package com.gitforge.vcs.gc;

/**
 * The set of still-needed objects could not be established, so nothing was
 * removed.
 *
 * <p>Thrown when an object the repository references cannot be read back —
 * missing, or damaged past parsing. What that object pointed at is then unknown,
 * and objects that are only reachable through it would look like garbage.
 *
 * <p>Failing here is the point. A sweep that carried on would delete exactly the
 * history a damaged repository still had, turning a fault that a restore could
 * fix into one it cannot.
 */
public class IncompleteReachabilityException extends RuntimeException {

    public IncompleteReachabilityException(String message) {
        super(message);
    }

    public IncompleteReachabilityException(String message, Throwable cause) {
        super(message, cause);
    }
}
