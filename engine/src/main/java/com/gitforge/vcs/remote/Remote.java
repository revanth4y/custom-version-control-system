package com.gitforge.vcs.remote;

import com.gitforge.vcs.ref.RemoteName;

/**
 * A repository elsewhere, and the name this repository knows it by.
 *
 * <p>The name is the one that appears under {@code refs/remotes/}, so it is
 * validated by the same rule that governs that directory rather than by one of
 * its own. The URL is not validated here: whether an address is one this server
 * may request depends on deployment policy, and a record that refused to hold a
 * previously-registered URL could not read its own configuration back.
 *
 * @param name what this repository calls it
 * @param url where it is
 */
public record Remote(String name, String url) {

    public Remote {
        RemoteName.validate(name);
        if (url == null || url.isBlank()) {
            throw new RemoteException("Remote " + name + " must have a URL");
        }
    }
}
