package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.diff.DiffLine;

/**
 * One line of a hunk.
 *
 * @param type CONTEXT, ADDED or REMOVED
 * @param oldNumber null on added lines
 * @param newNumber null on removed lines
 */
public record DiffLineResponse(String type, Integer oldNumber, Integer newNumber, String content) {

    public static DiffLineResponse from(DiffLine line) {
        return new DiffLineResponse(
                line.type().name(), line.oldNumber(), line.newNumber(), line.content());
    }
}
