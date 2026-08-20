package com.gitforge;

import com.gitforge.issue.IssueRepository;
import com.gitforge.repo.RepoRepository;
import com.gitforge.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

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

    /**
     * Repository storage for the whole suite.
     *
     * <p>Created once because the Spring context — and therefore the repository
     * factory holding this path — is cached across test classes. Its contents
     * are cleared before each test instead.
     */
    protected static final Path STORAGE_ROOT = createStorageRoot();

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("gitforge.storage.root", () -> STORAGE_ROOT.toString());
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("gitforge-test-storage");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create test storage root", ex);
        }
    }

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

    /**
     * The container and storage directory are shared across the suite, so each
     * test starts from an empty schema and empty repository storage.
     */
    @BeforeEach
    void resetState() {
        issueRepository.deleteAll();
        repoRepository.deleteAll();
        userRepository.deleteAll();
        clearStorage();
    }

    private static void clearStorage() {
        if (!Files.isDirectory(STORAGE_ROOT)) {
            return;
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            // Deepest first, so directories are empty by the time they are removed.
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(STORAGE_ROOT)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not clear test storage", ex);
        }
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
