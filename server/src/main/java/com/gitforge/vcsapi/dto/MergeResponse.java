package com.gitforge.vcsapi.dto;

import com.gitforge.vcs.repository.MergeOutcome;

import java.util.List;

/**
 * What a merge did, discriminated by {@code outcome}.
 *
 * <p>The four results carry genuinely different information, so fields that do
 * not apply are absent rather than filled with placeholders: a fast-forward has
 * no merge commit, and a conflict has no commit or tree at all.
 *
 * @param outcome ALREADY_UP_TO_DATE, FAST_FORWARDED, MERGED or CONFLICTED
 * @param head where our branch points now
 * @param cleanlyMerged for a conflict, the changes that would have applied
 */
public record MergeResponse(
        String outcome,
        String head,
        String mergeCommit,
        String tree,
        List<ConflictResponse> conflicts,
        List<ChangeResponse> cleanlyMerged) {

    public static MergeResponse from(MergeOutcome outcome) {
        return switch (outcome) {
            case MergeOutcome.AlreadyUpToDate upToDate -> new MergeResponse(
                    "ALREADY_UP_TO_DATE", upToDate.head().toHex(), null, null, List.of(), List.of());

            case MergeOutcome.FastForwarded fastForwarded -> new MergeResponse(
                    "FAST_FORWARDED", fastForwarded.newHead().toHex(), null, null, List.of(), List.of());

            case MergeOutcome.Merged merged -> new MergeResponse(
                    "MERGED",
                    merged.mergeCommit().toHex(),
                    merged.mergeCommit().toHex(),
                    merged.tree().toHex(),
                    List.of(),
                    List.of());

            case MergeOutcome.Conflicted conflicted -> new MergeResponse(
                    "CONFLICTED",
                    null,
                    null,
                    null,
                    conflicted.conflicts().stream().map(ConflictResponse::from).toList(),
                    conflicted.cleanlyMerged().stream().map(ChangeResponse::from).toList());
        };
    }
}
