package com.gitforge.vcsapi.dto;

import java.util.List;

/**
 * What a repository will tell another about its branches.
 *
 * <p>Branches only. HEAD is not advertised because it says which branch a
 * repository has checked out, which is nobody else's business and is not
 * something a fetch acts on. Remote-tracking refs are not advertised either: they
 * are this repository's record of a third party, and passing them on would let a
 * peer's view of the world propagate as though it were ours.
 *
 * @param refs every branch, with the commit it points at
 */
public record RemoteRefsResponse(List<RefEntry> refs) {

    /** One branch and its tip. */
    public record RefEntry(String branch, String commit) {
    }
}
