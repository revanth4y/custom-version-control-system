package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The integrity endpoint over HTTP.
 *
 * <p>The assertion that matters most is that a damaged repository answers
 * <strong>200</strong>. Every other read path turns
 * {@code CorruptObjectException} into a 500, which is right for them and would be
 * exactly wrong here: finding damage is this endpoint succeeding, and a scan that
 * reported a server error whenever it found something would be useless for the
 * one job it has.
 */
class IntegrityApiIT extends AbstractIntegrationTest {

    private static final String INTEGRITY = "/api/v1/repositories/octocat/%s/integrity";

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = registerAndLogin("octocat");
    }

    private void createRepo(String name) throws Exception {
        createRepo(name, "PUBLIC");
    }

    private void createRepo(String name, String visibility) throws Exception {
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","description":"a repo","visibility":"%s"}
                                """.formatted(name, visibility)))
                .andExpect(status().isCreated());
    }

    private void commit(String repo, String message, String path, String content) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/%s/commits".formatted(repo))
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"%s","changes":[
                                  {"operation":"PUT","path":"%s","content":"%s"}
                                ]}
                                """.formatted(message, path, content)))
                .andExpect(status().isCreated());
    }

    private String repoId(String name) throws Exception {
        String body = mockMvc.perform(get("/api/v1/repositories/octocat/%s".formatted(name)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asString();
    }

    /** Overwrites every stored object with bytes that are not a zlib stream. */
    private void corruptStorage(String repositoryId) throws IOException {
        Path objects = STORAGE_ROOT.resolve(repositoryId).resolve("objects");
        assertThat(objects).isDirectory();

        try (var paths = Files.walk(objects)) {
            List<Path> files = paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
            assertThat(files).isNotEmpty();

            for (Path file : files) {
                Files.write(file, "not a zlib stream".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void aHealthyRepositoryVerifiesEveryStoredObject() throws Exception {
        createRepo("healthy");
        commit("healthy", "Initial commit", "README.md", "hello");

        mockMvc.perform(get(INTEGRITY.formatted("healthy")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.damaged").isEmpty())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.checkedAt").exists())
                // A commit, its tree and one blob: proof the scan covers every
                // object type rather than only the file that was written.
                .andExpect(jsonPath("$.storedObjects").value(3))
                .andExpect(jsonPath("$.verified").value(3));
    }

    @Test
    void aDamagedRepositoryAnswersTwoHundredAndReportsWhatIsWrong() throws Exception {
        createRepo("damaged");
        commit("damaged", "Initial commit", "README.md", "hello");
        corruptStorage(repoId("damaged"));

        mockMvc.perform(get(INTEGRITY.formatted("damaged")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(false))
                .andExpect(jsonPath("$.damaged.length()").value(3))
                .andExpect(jsonPath("$.damaged[0].id").isString())
                .andExpect(jsonPath("$.damaged[0].reason").value("UNREADABLE"));
    }

    @Test
    void aDamagedRepositoryIsNeverReportedAsAServerError() throws Exception {
        createRepo("damaged");
        commit("damaged", "Initial commit", "README.md", "hello");
        corruptStorage(repoId("damaged"));

        // Stated separately from the assertion above because this is the
        // regression that matters: every other read path returns 500 here.
        mockMvc.perform(get(INTEGRITY.formatted("damaged")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void theReportNamesNoFilesystemPath() throws Exception {
        createRepo("damaged");
        commit("damaged", "Initial commit", "README.md", "hello");
        corruptStorage(repoId("damaged"));

        String body = mockMvc.perform(get(INTEGRITY.formatted("damaged")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain(STORAGE_ROOT.toString())
                .doesNotContain("/objects/")
                .doesNotContain(repoId("damaged"));
    }

    @Test
    void anEmptyRepositoryClaimsNoHealthBecauseNothingWasVerified() throws Exception {
        createRepo("empty");

        String body = mockMvc.perform(get(INTEGRITY.formatted("empty")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedObjects").value(0))
                .andExpect(jsonPath("$.verified").value(0))
                .andExpect(jsonPath("$.damaged").isEmpty())
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn().getResponse().getContentAsString();

        // Asserted on the wire rather than through JSONPath, which cannot tell an
        // explicit null from an absent field. The distinction is the point: the
        // client must receive "unknown", not a missing key it might read as false.
        assertThat(objectMapper.readTree(body).get("healthy").isNull()).isTrue();
        assertThat(body).contains("\"healthy\":null");
    }

    @Test
    void aPublicRepositoryIsReadableAnonymously() throws Exception {
        createRepo("public-work");
        commit("public-work", "Initial commit", "README.md", "hello");

        mockMvc.perform(get(INTEGRITY.formatted("public-work")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true));
    }

    @Test
    void aPrivateRepositoryIsHiddenFromAnonymousCallers() throws Exception {
        createRepo("secret", "PRIVATE");
        commit("secret", "Initial commit", "README.md", "hello");

        mockMvc.perform(get(INTEGRITY.formatted("secret")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPrivateRepositoryIsHiddenFromOtherSignedInUsers() throws Exception {
        createRepo("secret", "PRIVATE");
        commit("secret", "Initial commit", "README.md", "hello");
        String stranger = registerAndLogin("intruder");

        mockMvc.perform(get(INTEGRITY.formatted("secret")).header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPrivateRepositoryIsStillReadableByItsOwner() throws Exception {
        createRepo("secret", "PRIVATE");
        commit("secret", "Initial commit", "README.md", "hello");

        mockMvc.perform(get(INTEGRITY.formatted("secret")).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true));
    }

    @Test
    void anUnknownRepositoryIsNotFound() throws Exception {
        mockMvc.perform(get(INTEGRITY.formatted("no-such-repository")))
                .andExpect(status().isNotFound());
    }
}
