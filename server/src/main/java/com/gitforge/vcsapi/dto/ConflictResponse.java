package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.merge.MergeConflict;

/**
 * A path the merge could not resolve, and what each side had there.
 *
 * <p>A null side means the path was absent there, which is what distinguishes
 * the two directions of a modify/delete conflict.
 *
 * @param kind CONTENT, ADD_ADD, MODIFY_DELETE, MODE or TYPE
 */
public record ConflictResponse(
        String kind,
        String path,
        SideResponse base,
        SideResponse ours,
        SideResponse theirs) {

    /** What one side held at the conflicting path. */
    public record SideResponse(String mode, String id, boolean directory) {

        static SideResponse from(MergeConflict.Side side) {
            return new SideResponse(side.mode().value(), side.id().toHex(), side.isDirectory());
        }
    }

    public static ConflictResponse from(MergeConflict conflict) {
        return new ConflictResponse(
                conflict.kind().name(),
                conflict.path(),
                conflict.base().map(SideResponse::from).orElse(null),
                conflict.ours().map(SideResponse::from).orElse(null),
                conflict.theirs().map(SideResponse::from).orElse(null));
    }
}
