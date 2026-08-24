package com.gitforge.vcsapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gitforge.vcs.object.Commit;
import com.gitforge.vcs.object.TreeEntry;

/**
 * One entry in a directory listing.
 *
 * @param lastCommit the commit that last touched this path, present only when
 *     the caller asked for it and it was found inside the search window. Omitted
 *     from the response entirely when absent, so a caller that does not ask sees
 *     exactly the payload it saw before this field existed.
 */
public record TreeEntryResponse(
        String name,
        String path,
        String type,
        String mode,
        String id,
        @JsonInclude(JsonInclude.Include.NON_NULL) LastCommitResponse lastCommit) {

    public static TreeEntryResponse from(TreeEntry entry, String parentPath) {
        return from(entry, parentPath, null);
    }

    public static TreeEntryResponse from(TreeEntry entry, String parentPath, Commit lastCommit) {
        String path = parentPath == null || parentPath.isBlank()
                ? entry.name()
                : parentPath + "/" + entry.name();

        return new TreeEntryResponse(
                entry.name(),
                path,
                entry.isDirectory() ? "dir" : "file",
                entry.mode().value(),
                entry.id().toHex(),
                lastCommit == null ? null : LastCommitResponse.from(lastCommit));
    }
}
