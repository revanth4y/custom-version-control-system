package com.gitforge.user.dto;

import com.gitforge.user.User;

import java.time.Instant;
import java.util.UUID;

/** Public view of an account. Never carries the password hash or email of another user. */
public record UserResponse(
        UUID id,
        String username,
        String displayName,
        String bio,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getBio(),
                user.getCreatedAt());
    }
}
