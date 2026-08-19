package com.gitforge.common.error;

import org.springframework.http.HttpStatus;

/** The request is malformed in a way bean validation cannot express. */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }
}
