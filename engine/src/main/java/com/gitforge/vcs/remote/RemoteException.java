package com.gitforge.vcs.remote;

/** Something about a remote — its configuration, or a conversation with it — went wrong. */
public class RemoteException extends RuntimeException {

    public RemoteException(String message) {
        super(message);
    }

    public RemoteException(String message, Throwable cause) {
        super(message, cause);
    }
}
