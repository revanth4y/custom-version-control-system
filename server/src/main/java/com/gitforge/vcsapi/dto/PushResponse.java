package com.gitforge.vcsapi.dto;

/**
 * What a push did on the remote.
 *
 * @param branch the branch moved there
 * @param commit where it now points
 * @param sentObjects objects the remote asked for and were sent
 * @param storedObjects objects the remote reported writing
 */
public record PushResponse(String branch, String commit, int sentObjects, int storedObjects) {
}
