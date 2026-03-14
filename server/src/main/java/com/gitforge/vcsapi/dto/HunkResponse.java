package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.diff.Hunk;

import java.util.List;

public record HunkResponse(
        String header,
        int oldStart,
        int oldCount,
        int newStart,
        int newCount,
        List<DiffLineResponse> lines) {

    public static HunkResponse from(Hunk hunk) {
        return new HunkResponse(
                hunk.header(),
                hunk.oldStart(),
                hunk.oldCount(),
                hunk.newStart(),
                hunk.newCount(),
                hunk.lines().stream().map(DiffLineResponse::from).toList());
    }
}
