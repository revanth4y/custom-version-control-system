package com.gitforge.auth;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIT extends AbstractIntegrationTest {

    private static final String SIGNUP = """
            {"username":"octocat","email":"octocat@example.com","password":"correct-horse-battery"}
            """;

    @Test
    void signupReturnsTokenAndProfileWithoutLeakingCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(SIGNUP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                .andExpect(jsonPath("$.user.username").value("octocat"))
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                // The response must never carry password material or the email.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    void duplicateUsernameIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(SIGNUP))
                .andExpect(status().isCreated());

        String sameUsernameDifferentEmail = """
                {"username":"octocat","email":"other@example.com","password":"correct-horse-battery"}
                """;

        mockMvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(sameUsernameDifferentEmail))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(SIGNUP))
                .andExpect(status().isCreated());

        String sameEmailDifferentUsername = """
                {"username":"hubot","email":"OCTOCAT@example.com","password":"correct-horse-battery"}
                """;

        // Comparison is case-insensitive, so this collides with the existing account.
        mockMvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(sameEmailDifferentUsername))
                .andExpect(status().isConflict());
    }

    @Test
    void signupRejectsWeakPasswordAndInvalidEmail() throws Exception {
        String invalid = """
                {"username":"octocat","email":"not-an-email","password":"short"}
                """;

        mockMvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @Test
    void loginSucceedsWithCorrectPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(SIGNUP))
                .andExpect(status().isCreated());

        String login = """
                {"email":"octocat@example.com","password":"correct-horse-battery"}
                """;

        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json").content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void loginWithWrongPasswordIsUnauthorised() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup").contentType("application/json").content(SIGNUP))
                .andExpect(status().isCreated());

        String wrong = """
                {"email":"octocat@example.com","password":"not-the-password"}
                """;

        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json").content(wrong))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginForUnknownAccountIsUnauthorised() throws Exception {
        String unknown = """
                {"email":"nobody@example.com","password":"correct-horse-battery"}
                """;

        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json").content(unknown))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsTheAuthenticatedAccount() throws Exception {
        String token = registerAndLogin("octocat");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("octocat"));
    }

    @Test
    void meRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void meRejectsAGarbageToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer("not-a-real-token")))
                .andExpect(status().isUnauthorized());
    }
}
