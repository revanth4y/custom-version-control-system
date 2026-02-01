package com.gitforge.common.error;

import org.springframework.http.HttpStatus;

/** The requested resource does not exist, or the caller may not know that it does. */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
