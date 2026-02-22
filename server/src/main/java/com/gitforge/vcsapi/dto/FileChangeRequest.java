package com.gitforge.vcsapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One file to change in a commit.
 *
 * @param operation {@code PUT} to create or replace, {@code DELETE} to remove
 * @param content required for {@code PUT}, ignored for {@code DELETE}
 * @param encoding {@code utf-8} (default) or {@code base64} for binary content
 * @param mode {@code 100644} (default) or {@code 100755} for an executable file
 */
public record FileChangeRequest(
        @NotNull
        @Pattern(regexp = "PUT|DELETE", message = "must be PUT or DELETE")
        String operation,

        @NotBlank @Size(max = 4096) String path,

        String content,

        @Pattern(regexp = "utf-8|base64", message = "must be utf-8 or base64")
        String encoding,

        @Pattern(regexp = "100644|100755", message = "must be 100644 or 100755")
        String mode) {
}
