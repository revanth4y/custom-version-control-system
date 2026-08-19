package com.gitforge.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateIssueRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20_000) String body) {
}
