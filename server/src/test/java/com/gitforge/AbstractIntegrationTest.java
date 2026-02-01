package com.gitforge;

import com.gitforge.issue.IssueRepository;
import com.gitforge.repo.RepoRepository;
import com.gitforge.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base for HTTP-level tests: full application context, real PostgreSQL, requests
 * driven through the complete filter chain so security rules are genuinely exercised.
 */
@SpringBootTest(properties = {
        "gitforge.jwt.secret=integration-test-signing-secret-of-sufficient-length",
        "gitforge.cors.allowed-origins=http://localhost:5173"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private RepoRepository repoRepository;

    @Autowired
    private UserRepository userRepository;

    /** The container is shared across the suite, so each test starts from an empty schema. */
    @BeforeEach
    void resetDatabase() {
        issueRepository.deleteAll();
        repoRepository.deleteAll();
        userRepository.deleteAll();
    }

    protected String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    /** Registers an account and returns its bearer token. */
    protected String registerAndLogin(String username) throws Exception {
        String body = """
                {"username":"%s","email":"%s@example.com","password":"correct-horse-battery"}
                """.formatted(username, username);

        String response = mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType("application/json")
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("token").asString();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
