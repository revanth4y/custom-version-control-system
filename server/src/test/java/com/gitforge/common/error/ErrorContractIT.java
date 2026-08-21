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

    /**
     * These parameters travel in shareable URLs, so a stale or hand-edited link
     * must not look like an outage. ?status=bogus reached the catch-all and was
     * reported as a 500 before this was handled.
     */
    @Test
    void anUnconvertibleQueryParameterIsBadRequestNotServerError() throws Exception {
        String token = registerAndLogin("octocat");
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"demo","description":"a repo","visibility":"PUBLIC"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/issues").param("status", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                // The accepted values are our own constants, so naming them is
                // what makes the message actionable.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("OPEN")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CLOSED")))
                // The value the caller sent is arbitrary input and is not echoed back.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bogus"))));
    }

    @Test
    void aValidEnumQueryParameterStillWorks() throws Exception {
        String token = registerAndLogin("hubot");
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"demo","description":"a repo","visibility":"PUBLIC"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/repositories/hubot/demo/issues").param("status", "OPEN"))
                .andExpect(status().isOk());
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

    /**
     * A comparison link with a parameter dropped is a truncated URL, not a
     * broken server. These reached the catch-all and were answered 500.
     */
    @Test
    void aMissingRequiredQueryParameterIsBadRequest() throws Exception {
        seedRepository("octocat");

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/diff").param("head", "main"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                // Naming the parameter is what lets the caller repair the URL.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("base")));

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/diff").param("base", "main"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("head")));
    }

    @Test
    void aBlobRequestWithoutAPathIsBadRequest() throws Exception {
        seedRepository("hubot");

        mockMvc.perform(get("/api/v1/repositories/hubot/demo/blob").param("ref", "main"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("path")));
    }

    /**
     * The parameters that are genuinely optional must stay optional; a handler
     * that turned every absent parameter into a 400 would break these.
     */
    @Test
    void omittingAnOptionalParameterIsStillFine() throws Exception {
        seedRepository("monalisa");

        mockMvc.perform(get("/api/v1/repositories/monalisa/demo/tree"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repositories/monalisa/demo/commits"))
                .andExpect(status().isOk());
    }

    /** A public repository with one commit on main, so reads have something to find. */
    private void seedRepository(String username) throws Exception {
        String token = registerAndLogin(username);

        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"demo","description":"a repo","visibility":"PUBLIC"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/repositories/%s/demo/commits".formatted(username))
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"Initial commit","changes":[
                                  {"operation":"PUT","path":"README.md","content":"hello"}
                                ]}
                                """))
                .andExpect(status().isCreated());
    }
}
