package com.gitforge.vcs.remote;

import java.util.List;

/**
 * How this repository talks to one elsewhere.
 *
 * <p>An interface rather than a concrete client so the transfer logic can be
 * exercised against a peer that is not a socket. The algorithms in
 * {@link FetchService} and {@link PushService} are where the correctness lives —
 * what is asked for, in what order, and what is verified before anything moves —
 * and testing those through a real HTTP stack would test the stack instead.
 *
 * <p>The real cross-instance behaviour is still proven end to end; this only
 * means it is not the <em>only</em> way the logic is proven.
 */
public interface RemoteTransport {

    /** One branch on a peer, and where it points. */
    record RemoteBranch(String branch, String commit) {
    }

    /** Every branch the peer will admit to, with its tip. */
    List<RemoteBranch> advertise(Remote remote);

    /**
     * Which of {@code ids} the peer does not already hold.
     *
     * <p>Asked before sending, so a push carries what is actually needed rather
     * than everything reachable. The peer answers about its own store, which is
     * the only party that knows.
     */
    List<String> missing(Remote remote, List<String> ids);

    /** Fetches the named objects from the peer. */
    List<TransferredObject> objects(Remote remote, List<String> ids);

    /**
     * Sends objects to the peer, optionally asking it to move a branch afterwards.
     *
     * @param branch the branch to move, or null to send objects only
     * @param commit where that branch should point, or null when {@code branch} is
     * @return what the peer reported
     */
    ReceiveOutcome receive(
            Remote remote,
            String token,
            List<TransferredObject> objects,
            String branch,
            String commit);

    /**
     * What a peer said about a receive.
     *
     * @param storedObjects objects the peer wrote, excluding ones it already had
     * @param branch the branch it moved, or null if it was asked to move none
     * @param commit where that branch now points, or null
     */
    record ReceiveOutcome(int storedObjects, String branch, String commit) {
    }
}
