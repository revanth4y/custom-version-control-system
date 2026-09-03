package com.gitforge.vcs.object;

/**
 * An object could not be reconstructed, or did not hash to the id it was stored
 * under.
 *
 * <p>Raised instead of returning data that failed verification: in a
 * content-addressed store, silently handing back bytes that do not match their
 * id would let corruption propagate into every tree and commit built on top.
 */
public class CorruptObjectException extends RuntimeException {

    public CorruptObjectException(String message) {
        super(message);
    }

    public CorruptObjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
