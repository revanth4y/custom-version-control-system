package com.gitforge.repo.dto;

import com.gitforge.repo.RepoVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRepoRequest(

        @NotBlank
        @Size(max = 100)
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$",
                message = "must contain only letters, digits, '.', '_' or '-', and must start with a letter or digit")
        String name,

        @Size(max = 500)
        String description,

        @NotNull
        RepoVisibility visibility) {
}
