package com.gitforge.common.error;

import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.storage.ObjectStoreException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Translates exceptions into {@link ApiError} responses.
 *
 * <p>Internal failures are logged with their stack trace but reported to the
 * client as a generic message, so implementation details never leak.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(
                ex.getStatus().value(), ex.getCode(), ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();

        ApiError body = ApiError.validation("Request validation failed", request.getRequestURI(), fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * The request body could not be read at all — malformed JSON, a wrong type,
     * or bytes that are not valid UTF-8.
     *
     * <p>This is the caller's mistake, not a server fault. Without this it falls
     * through to the catch-all and is reported as a 500, which both misleads the
     * client and logs a stack trace for something the server handled correctly.
     *
     * <p>The parser's own message is not returned: it describes internal
     * structure and can quote arbitrary request bytes back to the caller.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(
                400, "MALFORMED_REQUEST", "Request body could not be read", request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(
            AuthenticationException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(401, "UNAUTHENTICATED", "Authentication required", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(403, "FORBIDDEN", "Access denied", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * A reference was not in the state the caller assumed.
     *
     * <p>The API services resolve the common cases precisely — an absent branch
     * as 404, a malformed name as 400 — so what reaches here is a genuine
     * conflict with existing state.
     */
    @ExceptionHandler(RefException.class)
    public ResponseEntity<ApiError> handleReference(RefException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(409, "CONFLICT", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * A request the version-control engine refused as invalid.
     *
     * <p>These messages describe the caller's mistake — an unknown path, a path
     * colliding with a directory — and are safe and useful to return.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(400, "BAD_REQUEST", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Stored data failed verification, or could not be read.
     *
     * <p>Reported as a server fault with the detail withheld: it says nothing the
     * caller can act on, and describes internal storage.
     */
    @ExceptionHandler({CorruptObjectException.class, ObjectStoreException.class})
    public ResponseEntity<ApiError> handleStorageFailure(RuntimeException ex, HttpServletRequest request) {
        log.error("Object store failure for {} {}", request.getMethod(), request.getRequestURI(), ex);

        ApiError body = ApiError.of(
                500, "STORAGE_ERROR", "The repository could not be read", request.getRequestURI());
        return ResponseEntity.internalServerError().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);

        ApiError body = ApiError.of(
                500, "INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI());
        return ResponseEntity.internalServerError().body(body);
    }
}
