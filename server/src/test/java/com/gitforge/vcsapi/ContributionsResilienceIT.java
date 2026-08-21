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
 * A profile survives one of its repositories being unreadable.
 *
 * <p>The contribution calendar aggregates every repository a person owns, and a
 * damaged object store used to propagate out of that loop as a 500 - a year of
 * real work hidden behind one unreadable file. These pin the behaviour that
 * replaced it: the bad repository is skipped, the rest are still counted.
 */
class ContributionsResilienceIT extends AbstractIntegrationTest {

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = registerAndLogin("octocat");
    }

    private void createRepo(String name) throws Exception {
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","description":"a repo","visibility":"PUBLIC"}
                                """.formatted(name)))
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

    /**
     * The order the aggregation walks repositories in, straight from the API the
     * profile page itself uses. The service orders by most recently updated, so
     * this is the same sequence {@code ContributionApiService} will see.
     */
    private List<String> repositoryOrder() throws Exception {
        String body = mockMvc.perform(get("/api/v1/users/octocat/repositories"))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).valueStream()
                .map(node -> node.get("name").asString())
                .toList();
    }

    /**
     * Replaces every stored object with garbage.
     *
     * <p>The bytes on disk are zlib-compressed, so text that is not a valid
     * stream fails in the inflater - the same way a truncated write or a bad
     * sector would. Every object goes, so the failure cannot depend on which one
     * the walk happens to reach first.
     */
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
    void oneCorruptRepositoryDoesNotEmptyTheWholeProfile() throws Exception {
        // Healthy first, corrupt second, so the corrupt one is the more recently
        // updated of the two.
        createRepo("healthy");
        commit("healthy", "Initial commit", "README.md", "hello");
        commit("healthy", "Second commit", "a.txt", "a");

        createRepo("damaged");
        commit("damaged", "Initial commit", "README.md", "hello");
        corruptStorage(repoId("damaged"));

        // The aggregation reaches the broken repository before the good one, so a
        // pass here cannot be an accident of ordering: were the failure not
        // contained, it would abort before "healthy" was ever counted.
        assertThat(repositoryOrder()).containsExactly("damaged", "healthy");

        mockMvc.perform(get("/api/v1/users/octocat/contributions"))
                .andExpect(status().isOk())
                // Two commits from the healthy repository, none from the broken one.
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.days").isArray());
    }

    /** The commits land on the day they were made, not merely in the total. */
    @Test
    void theHealthyRepositoryKeepsItsDailyCounts() throws Exception {
        createRepo("healthy");
        commit("healthy", "Initial commit", "README.md", "hello");

        createRepo("damaged");
        commit("damaged", "Initial commit", "README.md", "hello");
        corruptStorage(repoId("damaged"));

        String body = mockMvc.perform(get("/api/v1/users/octocat/contributions"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var days = objectMapper.readTree(body).get("days").valueStream()
                .filter(day -> day.get("count").asInt() > 0)
                .toList();

        assertThat(days).hasSize(1);
        assertThat(days.getFirst().get("count").asInt()).isEqualTo(1);
    }

    /**
     * Nothing about the failure reaches the caller. A profile is public; the
     * state of our storage is not the visitor's business, and naming a path or
     * an object id in the response would be a disclosure.
     */
    @Test
    void theResponseSaysNothingAboutTheDamage() throws Exception {
        createRepo("damaged");
        commit("damaged", "Initial commit", "README.md", "hello");
        String id = repoId("damaged");
        corruptStorage(id);

        String body = mockMvc.perform(get("/api/v1/users/octocat/contributions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("STORAGE_ERROR")
                .doesNotContain("corrupt")
                .doesNotContain("zlib")
                .doesNotContain(id)
                .doesNotContain(STORAGE_ROOT.toString());
    }

    /** Every repository being broken is still an answerable question. */
    @Test
    void aProfileOfEntirelyUnreadableRepositoriesIsEmptyRatherThanBroken() throws Exception {
        createRepo("damaged");
        commit("damaged", "Initial commit", "README.md", "hello");
        corruptStorage(repoId("damaged"));

        mockMvc.perform(get("/api/v1/users/octocat/contributions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    /**
     * The repository's own pages are a different matter: asking to read a broken
     * repository directly must still say so rather than pretend it is empty.
     */
    @Test
    void theBrokenRepositoryItselfStillReportsTheFailure() throws Exception {
        createRepo("damaged");
        commit("damaged", "Initial commit", "README.md", "hello");
        corruptStorage(repoId("damaged"));

        mockMvc.perform(get("/api/v1/repositories/octocat/damaged/tree"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("STORAGE_ERROR"));
    }
}
