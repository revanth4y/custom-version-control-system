package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.diff.TreeChange;

/**
 * One file-level difference.
 *
 * @param type {@code ADDED}, {@code DELETED} or {@code MODIFIED}
 */
public record ChangeResponse(
        String type,
        String path,
        String oldBlob,
        String newBlob,
        String oldMode,
        String newMode) {

    public static ChangeResponse from(TreeChange change) {
        return switch (change) {
            case TreeChange.Added added -> new ChangeResponse(
                    "ADDED", added.path(), null, added.blob().toHex(), null, added.mode().value());
            case TreeChange.Deleted deleted -> new ChangeResponse(
                    "DELETED", deleted.path(), deleted.blob().toHex(), null, deleted.mode().value(), null);
            case TreeChange.Modified modified -> new ChangeResponse(
                    "MODIFIED",
                    modified.path(),
                    modified.oldBlob().toHex(),
                    modified.newBlob().toHex(),
                    modified.oldMode().value(),
                    modified.newMode().value());
        };
    }
}
