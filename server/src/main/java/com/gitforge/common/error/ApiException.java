package com.gitforge.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base for errors that map directly onto an HTTP response. Carrying the status
 * and a stable machine-readable code on the exception keeps controllers free of
 * status-selection logic.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
