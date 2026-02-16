package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.object.TreeEntry;

/**
 * One entry in a directory listing.
 *
 * @param type {@code file} or {@code dir}
 * @param path the full path from the repository root
 */
public record TreeEntryResponse(String name, String path, String type, String mode, String id) {

    public static TreeEntryResponse from(TreeEntry entry, String parentPath) {
        String path = parentPath == null || parentPath.isBlank()
                ? entry.name()
                : parentPath + "/" + entry.name();

        return new TreeEntryResponse(
                entry.name(),
                path,
                entry.isDirectory() ? "dir" : "file",
                entry.mode().value(),
                entry.id().toHex());
    }
}
