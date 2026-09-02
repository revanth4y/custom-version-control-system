package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.remote.Remote;

/**
 * A registered remote.
 *
 * @param name what this repository calls it
 * @param url where it is
 */
public record RemoteResponse(String name, String url) {

    public static RemoteResponse from(Remote remote) {
        return new RemoteResponse(remote.name(), remote.url());
    }
}
