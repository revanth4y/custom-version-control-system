package com.gitforge.vcsapi.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A commit: a set of file changes recorded together against one branch.
 *
 * <p>Several files at once is the normal shape — a commit is a snapshot, so
 * changing one file is merely the smallest version of the same operation.
 */
public record CommitRequest(
        @NotBlank @Size(max = 255) String branch,
        @NotBlank @Size(max = 10_000) String message,
        @NotEmpty @Valid List<FileChangeRequest> changes) {
}
