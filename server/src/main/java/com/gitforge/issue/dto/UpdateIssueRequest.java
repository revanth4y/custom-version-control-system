package com.gitforge.issue.dto;

import com.gitforge.issue.IssueStatus;
import jakarta.validation.constraints.Size;

/** Partial update. Null fields are left unchanged. */
public record UpdateIssueRequest(
        @Size(max = 200) String title,
        @Size(max = 20_000) String body,
        IssueStatus status) {
}
