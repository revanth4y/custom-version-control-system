package com.gitforge.auth;

import com.gitforge.AbstractIntegrationTest;
import com.gitforge.security.AuthAttemptLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limiting as the endpoints actually expose it.
 *
 * <p>The limiter's own behaviour is covered by its unit test against a movable
 * clock. What these check is the wiring: that a rejected password reaches the
 * counter at all, that login and signup share it, and that the refusal is an
 * HTTP 429 carrying Retry-After rather than another 401.
 */
class AuthThrottlingIT extends AbstractIntegrationTest {

    private static final String OTHER_ADDRESS = "198.51.100.22";

    @Autowired
    private AuthAttemptLimiter limiter;

    @AfterEach
    void forgetTheSecondAddress() {
        limiter.recordSuccess(OTHER_ADDRESS);
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType("application/json")
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)));
    }

    private void exhaustAllowance() throws Exception {
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES; i++) {
            login("nobody@example.com", "wrong-password-" + i)
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aRejectedPasswordIsCounted() throws Exception {
        registerAndLogin("octocat");

        // Ten wrong passwords are answered normally; the eleventh is refused
        // outright. Before this, /login answered 401 seventeen times a second
        // for as long as anyone cared to keep asking.
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES; i++) {
            login("octocat@example.com", "not-the-password")
                    .andExpect(status().isUnauthorized());
        }

        login("octocat@example.com", "not-the-password")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void theRetryAfterHeaderIsWithinTheWindow() throws Exception {
        exhaustAllowance();

        String retryAfter = login("nobody@example.com", "wrong")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");

        assertThat(retryAfter).isNotNull();
        long seconds = Long.parseLong(retryAfter);
        assertThat(seconds).isBetween(1L, AuthAttemptLimiter.WINDOW.toSeconds());
    }

    /**
     * Signup rejects a name that is taken, which answers the same question a
     * failed login does. Sharing the counter closes that second door.
     */
    @Test
    void signupSharesTheCounterWithLogin() throws Exception {
        registerAndLogin("octocat");
        exhaustAllowance();

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content("""
                                {"username":"newcomer","email":"newcomer@example.com","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aSuccessfulLoginClearsTheCount() throws Exception {
        registerAndLogin("octocat");

        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES - 1; i++) {
            login("octocat@example.com", "not-the-password")
                    .andExpect(status().isUnauthorized());
        }

        login("octocat@example.com", "correct-horse-battery")
                .andExpect(status().isOk());

        // Someone who fumbles a password and then gets it right is not left one
        // mistake away from being locked out.
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES; i++) {
            login("octocat@example.com", "not-the-password")
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void oneAddressBeingThrottledDoesNotThrottleAnother() throws Exception {
        registerAndLogin("octocat");
        exhaustAllowance();

        login("octocat@example.com", "wrong")
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(from(OTHER_ADDRESS))
                        .contentType("application/json")
                        .content("""
                                {"email":"octocat@example.com","password":"correct-horse-battery"}
                                """))
                .andExpect(status().isOk());
    }

    /**
     * The refusal must not become an oracle of its own: an address that has run
     * out of attempts is told the same thing whether the account it is asking
     * about exists or not.
     */
    @Test
    void theRefusalRevealsNothingAboutTheAccount() throws Exception {
        registerAndLogin("octocat");
        exhaustAllowance();

        String forRealAccount = login("octocat@example.com", "wrong")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();
        String forUnknownAccount = login("nobody-at-all@example.com", "wrong")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(forRealAccount).get("message").asString())
                .isEqualTo(objectMapper.readTree(forUnknownAccount).get("message").asString())
                .doesNotContain("octocat");
    }

    /** Throttling is scoped to authentication; it must not take the site down. */
    @Test
    void throttlingDoesNotBlockOrdinaryReads() throws Exception {
        String token = registerAndLogin("octocat");
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"demo","description":"a repo","visibility":"PUBLIC"}
                                """))
                .andExpect(status().isCreated());

        exhaustAllowance();

        mockMvc.perform(get("/api/v1/repositories/octocat/demo"))
                .andExpect(status().isOk());
    }
}
