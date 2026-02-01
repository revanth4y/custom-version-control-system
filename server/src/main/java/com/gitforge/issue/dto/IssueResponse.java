package com.gitforge.issue.dto;

import com.gitforge.issue.Issue;
import com.gitforge.issue.IssueStatus;

import java.time.Instant;
import java.util.UUID;

public record IssueResponse(
        UUID id,
        int number,
        String title,
        String body,
        IssueStatus status,
        String authorUsername,
        Instant createdAt,
        Instant updatedAt) {

    public static IssueResponse from(Issue issue) {
        return new IssueResponse(
                issue.getId(),
                issue.getNumber(),
                issue.getTitle(),
                issue.getBody(),
                issue.getStatus(),
                // Null once the author's account has been deleted.
                issue.getAuthor() == null ? null : issue.getAuthor().getUsername(),
                issue.getCreatedAt(),
                issue.getUpdatedAt());
    }
}
