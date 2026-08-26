package com.gitforge.common.error;

import org.springframework.http.HttpStatus;

/**
 * The content involved is larger than this API will carry.
 *
 * <p>Shares its code with the transport filter's refusal of an oversized request
 * body, so "too large" reads the same to a client wherever it is decided.
 *
 * <p>Used for reads. A write that is too large is refused as a bad request,
 * which is what that path has always answered and what its callers expect.
 */
public class PayloadTooLargeException extends ApiException {

    public PayloadTooLargeException(String message) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", message);
    }
}
