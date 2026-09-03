package com.gitforge.vcs.remote;

/**
 * A push would have moved a branch somewhere its current tip does not lead.
 *
 * <p>Its own type because it is the one refusal a caller can do something useful
 * about — fetch, merge, push again — as opposed to the failures that mean the
 * transfer itself was wrong. Callers that map failures to status codes need to
 * tell the two apart.
 */
public class NotFastForwardException extends RemoteException {

    public NotFastForwardException(String message) {
        super(message);
    }
}
