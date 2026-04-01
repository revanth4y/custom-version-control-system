package com.gitforge.common.web;

import com.gitforge.common.error.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bounds how much of a request body the server will read.
 *
 * <p>Tomcat has no limit for a JSON body - {@code max-http-form-post-size}
 * governs form encoding only - so before this a single request could ask the
 * server to buffer as much as the client cared to send. A twelve megabyte blob
 * was accepted without complaint, and nothing prevented twelve gigabytes.
 *
 * <p>Two defences, because one is not enough. The declared {@code Content-Length}
 * is checked first, which rejects the common case without reading anything at
 * all. A client can lie, or use chunked encoding and declare nothing, so the
 * stream itself also counts what it hands over and fails once the limit is
 * passed - by then Jackson is mid-parse, so the request cannot complete and
 * nothing is written.
 */
@Component
@Order(RequestSizeLimitFilter.ORDER)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /** Ahead of Spring Security: there is no point authenticating a body we will refuse. */
    static final int ORDER = -200;

    /**
     * Sixteen megabytes.
     *
     * <p>Sized from what the API can legitimately carry: one maximum blob of ten
     * megabytes, base64-encoded to 13.34, plus the surrounding JSON. Anything
     * larger is not a commit anyone is making through a web interface.
     */
    public static final long MAX_REQUEST_BYTES = 16L * 1024 * 1024;

    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) {
            reject(request, response);
            return;
        }
        chain.doFilter(new LimitedRequest(request), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                ApiError.of(
                        HttpStatus.PAYLOAD_TOO_LARGE.value(),
                        "PAYLOAD_TOO_LARGE",
                        "The request body may be at most %d MB".formatted(MAX_REQUEST_BYTES / 1024 / 1024),
                        request.getRequestURI()));
    }

    /** Thrown mid-read when a body outgrows the limit it did not declare. */
    static final class RequestTooLargeException extends IOException {
        RequestTooLargeException() {
            super("Request body exceeded " + MAX_REQUEST_BYTES + " bytes");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();

            return new ServletInputStream() {
                private long read;

                private void count(int bytes) throws IOException {
                    if (bytes > 0) {
                        read += bytes;
                        if (read > MAX_REQUEST_BYTES) {
                            throw new RequestTooLargeException();
                        }
                    }
                }

                @Override
                public int read() throws IOException {
                    int value = delegate.read();
                    count(value == -1 ? 0 : 1);
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = delegate.read(buffer, offset, length);
                    count(count);
                    return count;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
