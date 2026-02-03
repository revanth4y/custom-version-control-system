package com.gitforge.auth.dto;

import com.gitforge.user.dto.UserResponse;

public record AuthResponse(
        String token,
        long expiresInSeconds,
        UserResponse user) {
}
