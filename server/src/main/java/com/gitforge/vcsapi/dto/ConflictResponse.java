package com.gitforge.vcsapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gitforge.vcs.merge.ConflictRegion;
import com.gitforge.vcs.merge.LineRange;
import com.gitforge.vcs.merge.MergeConflict;

import java.util.List;

/**
 * A path the merge could not resolve, and what each side had there.
 *
 * <p>A null side means the path was absent there, which is what distinguishes
 * the two directions of a modify/delete conflict.
 *
 * @param kind CONTENT, ADD_ADD, MODIFY_DELETE, MODE or TYPE
 * @param regions the stretches of the file that disagree, omitted entirely
 *     rather than sent empty. Present only where a line-level merge ran and
 *     found something it could not reconcile; absent for binary files,
 *     directories, and every other conflict the line view cannot speak about.
 *     A client that ignores this field renders exactly as it did before the
 *     field existed.
 */
public record ConflictResponse(
        String kind,
        String path,
        SideResponse base,
        SideResponse ours,
        SideResponse theirs,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<RegionResponse> regions) {

    /** What one side held at the conflicting path. */
    public record SideResponse(String mode, String id, boolean directory) {

        static SideResponse from(MergeConflict.Side side) {
            return new SideResponse(side.mode().value(), side.id().toHex(), side.isDirectory());
        }
    }

    /**
     * One disagreeing stretch, as line ranges into the three files.
     *
     * <p>Half-open and one-based, so {@code start} equal to {@code end} says
     * that side contributes no lines there rather than saying nothing.
     */
    public record RegionResponse(RangeResponse base, RangeResponse ours, RangeResponse theirs) {

        static RegionResponse from(ConflictRegion region) {
            return new RegionResponse(
                    RangeResponse.from(region.base()),
                    RangeResponse.from(region.ours()),
                    RangeResponse.from(region.theirs()));
        }
    }

    /** A half-open, one-based run of lines. */
    public record RangeResponse(int start, int end) {

        static RangeResponse from(LineRange range) {
            return new RangeResponse(range.start(), range.end());
        }
    }

    public static ConflictResponse from(MergeConflict conflict) {
        return new ConflictResponse(
                conflict.kind().name(),
                conflict.path(),
                conflict.base().map(SideResponse::from).orElse(null),
                conflict.ours().map(SideResponse::from).orElse(null),
                conflict.theirs().map(SideResponse::from).orElse(null),
                conflict.regions().stream().map(RegionResponse::from).toList());
    }
}
