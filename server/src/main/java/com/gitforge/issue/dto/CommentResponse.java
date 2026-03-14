package com.gitforge.issue.dto;

import com.gitforge.issue.IssueComment;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        String body,
        String authorUsername,
        Instant createdAt,
        Instant updatedAt) {

    public static CommentResponse from(IssueComment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getBody(),
                // Null once the author's account has been deleted.
                comment.getAuthor() == null ? null : comment.getAuthor().getUsername(),
                comment.getCreatedAt(),
                comment.getUpdatedAt());
    }
}
