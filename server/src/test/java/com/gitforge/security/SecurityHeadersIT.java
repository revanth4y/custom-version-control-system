package com.gitforge.security;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The headers that actually leave the server.
 *
 * <p>Checked against a running instance before anything was added, because a
 * framework's defaults are a claim until they are observed. Spring Security was
 * already sending {@code nosniff}, {@code X-Frame-Options: DENY},
 * {@code Cache-Control: no-store} and {@code X-XSS-Protection: 0} — the last of
 * which is the modern correct value rather than an oversight. Three were absent,
 * and two of those were worth adding.
 *
 * <p>The policy added is the empty one. Every response here is JSON: it loads no
 * script, no stylesheet, no font and no image, and it is never a document. So
 * {@code default-src 'none'} forbids exactly the set of things that are asked
 * for, which is why it can be stated outright rather than tuned — a policy that
 * permits nothing cannot break an application that requests nothing. What it
 * covers is the case where a response is rendered instead of parsed: a browser
 * opened at an API URL, an error body reflected somewhere, a content type
 * ignored.
 *
 * <p><strong>What is deliberately absent is asserted too.</strong>
 * {@code Strict-Transport-Security} is not sent, because this deployment
 * terminates HTTP: an instruction never to use HTTP again would make the
 * application unreachable while promising a protection it does not have.
 * {@code Permissions-Policy} is not sent, because a JSON response has no
 * document to deny a camera to. Both would have looked like security. Asserting
 * their absence is what stops one being added later on the strength of a
 * checklist rather than a reason.
 */
class SecurityHeadersIT extends AbstractIntegrationTest {

    /** Anonymous, unauthenticated, and about as public as this API gets. */
    private static final String PUBLIC_ENDPOINT = "/api/v1/health";

    @Test
    @DisplayName("a plain response carries the full set")
    void headersOnAnOrdinaryResponse() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    @DisplayName("and so does a rejected one, which is where a body is most likely to be rendered")
    void headersOnAnErrorResponse() throws Exception {
        // An unauthenticated write. The entry point writes this response itself,
        // outside the controllers, which is exactly the path that would miss a
        // header applied by a controller advice rather than by the filter chain.
        mockMvc.perform(post("/api/v1/repositories")
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("and a 404, which Spring produces without any controller at all")
    void headersOnNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/nothing-is-here"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'none'")));
    }

    @Test
    @DisplayName("HSTS is not sent, because this deployment is not HTTPS")
    void noStrictTransportSecurity() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    @DisplayName("nor a permissions policy, which a JSON response has nothing to say about")
    void noPermissionsPolicy() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(header().doesNotExist("Permissions-Policy"));
    }

    @Test
    @DisplayName("the policy forbids framing twice, in both spellings browsers use")
    void framingIsRefusedBothWays() throws Exception {
        mockMvc.perform(get(PUBLIC_ENDPOINT))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    }
}
