package com.gitforge.vcs.ref;

/** A reference could not be read, written, or named. */
public class RefException extends RuntimeException {

    public RefException(String message) {
        super(message);
    }

    public RefException(String message, Throwable cause) {
        super(message, cause);
    }
}
