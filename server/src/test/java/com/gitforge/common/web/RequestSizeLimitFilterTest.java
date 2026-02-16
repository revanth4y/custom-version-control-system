package com.gitforge.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The body-size filter driven directly, so both of its defences can be seen
 * working.
 *
 * <p>The declared-length check is easy to reach over HTTP. The counting stream
 * is not: it only matters when the length is absent or untrue, which a normal
 * client never does. Here the request is built to report no length at all - what
 * chunked transfer encoding looks like to the servlet API - so the second
 * defence is exercised rather than assumed.
 */
class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(JsonMapper.builder().build());

    /** A request that declares no length, as a chunked one does. */
    private static final class UndeclaredLength extends MockHttpServletRequest {
        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }
    }

    /** A request that claims a length it does not have. */
    private static final class DeclaredLength extends MockHttpServletRequest {
        private final long declared;

        DeclaredLength(long declared) {
            this.declared = declared;
        }

        @Override
        public long getContentLengthLong() {
            return declared;
        }
    }

    private static MockHttpServletRequest withBody(MockHttpServletRequest request, int size) {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/repositories/octocat/demo/commits");
        request.setContentType("application/json");
        request.setContent(new byte[size]);
        return request;
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedWithoutReadingTheBody() throws Exception {
        MockHttpServletRequest request =
                withBody(new DeclaredLength(RequestSizeLimitFilter.MAX_REQUEST_BYTES + 1), 0);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        // Nothing downstream ran, so no body was buffered and no work was done
        // on behalf of a request that was never going to be accepted.
        assertThat(reached).isFalse();
    }

    @Test
    void anUndeclaredLengthOverTheLimitFailsMidRead() throws Exception {
        MockHttpServletRequest request =
                withBody(new UndeclaredLength(), (int) RequestSizeLimitFilter.MAX_REQUEST_BYTES + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Stands in for Jackson: the filter cannot know the size in advance, so
        // the read itself is what has to fail.
        FilterChain readsEverything = (req, res) -> req.getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(request, response, readsEverything))
                .isInstanceOf(RequestSizeLimitFilter.RequestTooLargeException.class);
    }

    /** The one-byte read has its own counter, and it has to count too. */
    @Test
    void anUndeclaredLengthIsCountedOneByteAtATime() throws Exception {
        MockHttpServletRequest request =
                withBody(new UndeclaredLength(), (int) RequestSizeLimitFilter.MAX_REQUEST_BYTES + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain readsByteByByte = (req, res) -> {
            InputStream body = req.getInputStream();
            while (body.read() != -1) {
                // Reading is the point; the filter is expected to interrupt it.
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, readsByteByByte))
                .isInstanceOf(RequestSizeLimitFilter.RequestTooLargeException.class);
    }

    @Test
    void aBodyUnderTheLimitIsPassedThroughIntact() throws Exception {
        byte[] body = "{\"branch\":\"main\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = withBody(new UndeclaredLength(), 0);
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> seen = new AtomicReference<>();

        filter.doFilter(request, response, (ServletRequest req, ServletResponse res) ->
                seen.set(req.getInputStream().readAllBytes()));

        assertThat(response.getStatus()).isEqualTo(200);
        // The wrapper counts but must not alter or truncate what it hands on.
        assertThat(seen.get()).isEqualTo(body);
    }

    @Test
    void aBodyExactlyAtTheLimitIsAccepted() throws Exception {
        MockHttpServletRequest request =
                withBody(new DeclaredLength(RequestSizeLimitFilter.MAX_REQUEST_BYTES),
                        (int) RequestSizeLimitFilter.MAX_REQUEST_BYTES);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Integer> read = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> read.set(req.getInputStream().readAllBytes().length));

        // The limit is a maximum, not a threshold to stop short of.
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(read.get()).isEqualTo((int) RequestSizeLimitFilter.MAX_REQUEST_BYTES);
    }

    /** A GET carries no body and must not be delayed by any of this. */
    @Test
    void aRequestWithNoBodyIsUnaffected() throws IOException, jakarta.servlet.ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/repositories");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
