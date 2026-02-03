package com.gitforge.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(

        @NotBlank
        @Size(max = 39)
        @Pattern(
                regexp = "^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9]))*$",
                message = "must contain only letters, digits and single hyphens, and cannot start or end with a hyphen")
        String username,

        @NotBlank @Email @Size(max = 254)
        String email,

        @NotBlank
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password) {
}
