package com.gitforge.common.error;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Requests that fail before reaching a controller.
 *
 * <p>Spring reports an unmatched route, a wrong method and an unacceptable
 * content type as exceptions. Without explicit handlers they reach the
 * catch-all and are answered with 500, which tells the caller the server broke
 * when in fact the request did — and buries a stack trace in the log for every
 * stray request. These assert the status codes the HTTP contract requires.
 */
class ErrorContractIT extends AbstractIntegrationTest {

    @Test
    void unmatchedPathIsNotFound() throws Exception {
        String token = registerAndLogin("octocat");

        mockMvc.perform(get("/api/v1/does-not-exist").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * Without credentials the security filter chain rejects the request before
     * routing, so an unknown path is answered 401 rather than 404. That is the
     * behaviour worth keeping: a 404 here would tell an anonymous caller which
     * endpoints exist.
     */
    @Test
    void unmatchedPathDoesNotRevealItselfToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongMethodIsMethodNotAllowedAndAdvertisesTheAlternatives() throws Exception {
        String token = registerAndLogin("octocat");

        mockMvc.perform(patch("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().exists("Allow"));
    }

    @Test
    void unsupportedContentTypeIsUnsupportedMediaType() throws Exception {
        String token = registerAndLogin("octocat");

        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("text/plain")
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void malformedJsonIsBadRequestNotServerError() throws Exception {
        String token = registerAndLogin("octocat");

        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
