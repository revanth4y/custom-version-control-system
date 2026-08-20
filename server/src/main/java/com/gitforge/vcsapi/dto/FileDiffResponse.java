package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.diff.FileDiff;

import java.util.List;

/**
 * How one file changed, with its hunks where a line diff is meaningful.
 *
 * @param binary true when the content is not text, so there are no hunks
 * @param tooLarge true when the diff was skipped to bound the work; distinct
 *     from binary so a client can say which reason applies
 */
public record FileDiffResponse(
        String path,
        String status,
        String oldBlob,
        String newBlob,
        String oldMode,
        String newMode,
        boolean binary,
        boolean tooLarge,
        int additions,
        int deletions,
        int oldSize,
        int newSize,
        List<HunkResponse> hunks) {

    public static FileDiffResponse from(FileDiff diff) {
        return new FileDiffResponse(
                diff.path(),
                diff.status().name(),
                diff.oldBlob() == null ? null : diff.oldBlob().toHex(),
                diff.newBlob() == null ? null : diff.newBlob().toHex(),
                diff.oldMode() == null ? null : diff.oldMode().value(),
                diff.newMode() == null ? null : diff.newMode().value(),
                diff.binary(),
                diff.tooLarge(),
                diff.additions(),
                diff.deletions(),
                diff.oldSize(),
                diff.newSize(),
                diff.hunks().stream().map(HunkResponse::from).toList());
    }
}
