package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param startPoint what the branch should point at: a branch name,
 *     {@code HEAD}, or a full commit id
 */
public record CreateBranchRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String startPoint) {
}
