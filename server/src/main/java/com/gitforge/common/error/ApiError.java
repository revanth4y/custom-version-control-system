package com.gitforge.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error payload. Every failing request returns this shape so the client
 * never has to branch on which layer produced the failure.
 *
 * @param fieldErrors populated only for validation failures
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path, null);
    }

    public static ApiError validation(String message, String path, List<FieldError> fieldErrors) {
        return new ApiError(Instant.now(), 400, "VALIDATION_FAILED", message, path, fieldErrors);
    }
}
