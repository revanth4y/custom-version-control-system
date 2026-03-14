package com.gitforge.vcsapi.dto;

import java.util.List;

/**
 * A complete diff between two revisions.
 *
 * @param totalAdditions lines added across every file
 * @param totalDeletions lines removed across every file
 */
public record DiffResultResponse(
        String base,
        String head,
        int filesChanged,
        int totalAdditions,
        int totalDeletions,
        List<FileDiffResponse> files) {

    public static DiffResultResponse of(String base, String head, List<FileDiffResponse> files) {
        return new DiffResultResponse(
                base,
                head,
                files.size(),
                files.stream().mapToInt(FileDiffResponse::additions).sum(),
                files.stream().mapToInt(FileDiffResponse::deletions).sum(),
                files);
    }
}
