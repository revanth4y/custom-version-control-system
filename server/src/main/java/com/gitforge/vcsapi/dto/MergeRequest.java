package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Merging one branch into another.
 *
 * <p>Only {@code ourBranch} can move; the branch being merged in is never
 * touched.
 *
 * @param message optional; a default is generated when absent
 */
public record MergeRequest(
        @NotBlank @Size(max = 255) String ourBranch,
        @NotBlank @Size(max = 255) String theirBranch,
        @Size(max = 10_000) String message) {
}
