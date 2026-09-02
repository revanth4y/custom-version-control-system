package com.gitforge.vcsapi.dto;

/**
 * What a repository did with objects it was sent.
 *
 * @param storedObjects objects written, excluding ones it already held
 * @param branch the branch moved, or null if none was asked for
 * @param commit where that branch now points, or null
 */
public record ReceiveObjectsResponse(int storedObjects, String branch, String commit) {
}
