package com.gitforge.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Partial update of the caller's own account. Null fields are left unchanged. */
public record UpdateUserRequest(
        @Size(max = 80) String displayName,
        @Size(max = 500) String bio,
        @Email @Size(max = 254) String email,
        @Size(min = 8, max = 72) String password) {
}
