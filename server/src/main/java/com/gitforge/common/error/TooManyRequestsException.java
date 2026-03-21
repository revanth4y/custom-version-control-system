package com.gitforge.common.error;

import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * The caller has been refused for making too many attempts too quickly.
 *
 * <p>Carries how long they must wait so the response can say so in
 * {@code Retry-After}. The message never mentions why an attempt failed, or
 * whether the account involved exists - throttling must not become the oracle
 * that the throttled endpoint was protecting.
 */
public class TooManyRequestsException extends ApiException {

    private final Duration retryAfter;

    public TooManyRequestsException(String message, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", message);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
