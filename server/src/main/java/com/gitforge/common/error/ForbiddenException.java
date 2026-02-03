package com.gitforge.common.error;

import org.springframework.http.HttpStatus;

/** The caller is authenticated but lacks permission for this action. */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
