package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Writing a single file, as a convenience over the multi-file commit API.
 *
 * <p>{@code content} is required but may be empty: an empty file is legitimate
 * content, not a missing value.
 *
 * @param encoding {@code utf-8} (default) or {@code base64}
 * @param mode {@code 100644} (default) or {@code 100755}
 */
public record PutContentRequest(
        @NotBlank @Size(max = 255) String branch,
        @NotBlank @Size(max = 4096) String path,
        @NotNull String content,
        @Pattern(regexp = "utf-8|base64", message = "must be utf-8 or base64") String encoding,
        @Pattern(regexp = "100644|100755", message = "must be 100644 or 100755") String mode,
        @NotBlank @Size(max = 10_000) String message) {
}
