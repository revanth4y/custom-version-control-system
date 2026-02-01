package com.gitforge.common.error;

import org.springframework.http.HttpStatus;

/** The request collides with existing state, such as a duplicate name. */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
