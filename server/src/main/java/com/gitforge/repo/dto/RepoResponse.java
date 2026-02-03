package com.gitforge.repo.dto;

import com.gitforge.repo.Repo;
import com.gitforge.repo.RepoVisibility;

import java.time.Instant;
import java.util.UUID;

public record RepoResponse(
        UUID id,
        String name,
        String description,
        RepoVisibility visibility,
        String ownerUsername,
        Instant createdAt,
        Instant updatedAt) {

    public static RepoResponse from(Repo repo) {
        return new RepoResponse(
                repo.getId(),
                repo.getName(),
                repo.getDescription(),
                repo.getVisibility(),
                repo.getOwner().getUsername(),
                repo.getCreatedAt(),
                repo.getUpdatedAt());
    }
}
