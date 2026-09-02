package com.gitforge.common.error;

import com.gitforge.vcs.object.AmbiguousObjectIdException;
import com.gitforge.vcs.object.CorruptObjectException;
import com.gitforge.vcs.ref.RefException;
import com.gitforge.vcs.remote.NotFastForwardException;
import com.gitforge.vcs.remote.RemoteException;
import com.gitforge.vcs.storage.ObjectStoreException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Translates exceptions into {@link ApiError} responses.
 *
 * <p>Internal failures are logged with their stack trace but reported to the
 * client as a generic message, so implementation details never leak.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Throttled. Carries {@code Retry-After} so a well-behaved client knows when
     * to come back rather than hammering until it happens to be let through.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleTooManyRequests(
            TooManyRequestsException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(429, ex.getCode(), ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, ex.getRetryAfter().toSeconds())))
                .body(body);
    }

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
     * An abbreviated object id matched more than one object.
     *
     * <p>A conflict rather than a bad request: the caller's input is well formed
     * and the repository is intact. What they asked for is real, there is simply
     * more than one of it, and the message names the collisions so the next
     * attempt can be longer rather than another guess.
     */
    @ExceptionHandler(AmbiguousObjectIdException.class)
    public ResponseEntity<ApiError> handleAmbiguousObjectId(
            AmbiguousObjectIdException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(409, "CONFLICT", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
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
     * A push would have moved a branch somewhere its current tip does not lead.
     *
     * <p>409 rather than 400, because nothing about the request was malformed: the
     * caller asked for something coherent and the repository's state is why it
     * cannot happen. It is also the one remote failure a caller can act on — fetch,
     * merge, push again — which is why it is told apart from the rest.
     */
    @ExceptionHandler(NotFastForwardException.class)
    public ResponseEntity<ApiError> handleNotFastForward(
            NotFastForwardException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(409, "CONFLICT", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * A remote operation that could not be completed.
     *
     * <p>422 rather than 400 or 502. The request was well-formed and the caller is
     * entitled to make it; what failed is the transfer — an object that did not
     * hash to its id, a history that arrived incomplete, a peer that would not
     * answer. Saying "unprocessable" keeps that distinct from a malformed request
     * on one side and a broken server on the other.
     *
     * <p>The message is returned because every one of them describes something the
     * caller can act on, and none of them exposes anything about this server that a
     * peer did not already tell it.
     */
    @ExceptionHandler(RemoteException.class)
    public ResponseEntity<ApiError> handleRemote(RemoteException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(
                422, "REMOTE_TRANSFER_FAILED", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
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

    /**
     * The request never reached a controller: no route matched the path, the
     * route exists but not for this method, or the body's media type is not one
     * any handler accepts.
     *
     * <p>All three are the caller's mistake, but Spring surfaces them as
     * exceptions that would otherwise reach the catch-all below and be reported
     * as 500 - telling the client the server is broken when the request was, and
     * logging a stack trace for each one. They are mapped to their proper status
     * codes here.
     *
     * <p>{@code Allow} and {@code Accept} carry the supported alternatives, which
     * is what makes a 405 or 415 actionable rather than merely correct.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoRoute(
            NoResourceFoundException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(404, "NOT_FOUND", "No such endpoint", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(
                405,
                "METHOD_NOT_ALLOWED",
                "%s is not supported for this endpoint".formatted(ex.getMethod()),
                request.getRequestURI());

        BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> allowed = ex.getSupportedHttpMethods();
        if (allowed != null && !allowed.isEmpty()) {
            response.allow(allowed.toArray(new HttpMethod[0]));
        }
        return response.body(body);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(
                415,
                "UNSUPPORTED_MEDIA_TYPE",
                "This endpoint does not accept that content type",
                request.getRequestURI());

        BodyBuilder response = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        List<MediaType> supported = ex.getSupportedMediaTypes();
        if (!supported.isEmpty()) {
            response.header("Accept", MediaType.toString(supported));
        }
        return response.body(body);
    }

    /**
     * A request parameter that could not be converted to the type the handler
     * declares - {@code ?status=bogus} against an enum, say.
     *
     * <p>Without this the conversion failure reaches the catch-all and is
     * reported as a 500, which blames the server for a value the caller chose.
     * It matters more than it looks: these parameters travel in shareable URLs,
     * so a stale or hand-edited link would look like an outage.
     *
     * <p>For an enum the accepted values are listed, because they are our own
     * constants and naming them is what makes the error actionable. The value
     * that was actually sent is not echoed back - it is arbitrary input, and the
     * parameter name alone identifies the problem.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        Class<?> required = ex.getRequiredType();
        String detail;
        if (required != null && required.isEnum()) {
            String accepted = Arrays.stream(required.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            detail = "'%s' must be one of: %s".formatted(ex.getName(), accepted);
        } else {
            detail = "'%s' is not in the expected format".formatted(ex.getName());
        }

        ApiError body = ApiError.of(400, "BAD_REQUEST", detail, request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * A required query parameter was not sent at all.
     *
     * <p>The neighbouring case: there the value could not be converted, here
     * there was no value. Spring raises this before the handler runs, and
     * without a mapping it reached the catch-all and became a 500 - telling the
     * caller the server broke when the request was simply incomplete. A
     * truncated compare link is the ordinary way to arrive here, so the response
     * names the parameter, which is the whole of what the caller needs to fix.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        ApiError body = ApiError.of(
                400,
                "BAD_REQUEST",
                "Required parameter '%s' is missing".formatted(ex.getParameterName()),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);

        ApiError body = ApiError.of(
                500, "INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI());
        return ResponseEntity.internalServerError().body(body);
    }
}
