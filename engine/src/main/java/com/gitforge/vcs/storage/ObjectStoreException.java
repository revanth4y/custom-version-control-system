package com.gitforge.vcs.storage;

/** A store operation failed for reasons outside the caller's control, such as I/O. */
public class ObjectStoreException extends RuntimeException {

    public ObjectStoreException(String message) {
        super(message);
    }

    public ObjectStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
