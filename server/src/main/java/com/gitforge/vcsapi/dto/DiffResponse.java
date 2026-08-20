package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.diff.TreeDiff;

import java.util.List;

/**
 * A set of differences, with counts per kind so a caller can summarise without
 * walking the list.
 */
public record DiffResponse(List<ChangeResponse> changes, int added, int deleted, int modified) {

    public static DiffResponse from(TreeDiff diff) {
        return new DiffResponse(
                diff.changes().stream().map(ChangeResponse::from).toList(),
                diff.added().size(),
                diff.deleted().size(),
                diff.modified().size());
    }
}
