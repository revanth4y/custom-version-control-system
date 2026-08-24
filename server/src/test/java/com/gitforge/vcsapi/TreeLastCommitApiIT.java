package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The opt-in last-commit data on a directory listing.
 *
 * <p>The behaviour worth pinning is the promise made to existing callers: ask
 * for nothing and the response is exactly what it was before this field
 * existed, down to the bytes.
 */
class TreeLastCommitApiIT extends AbstractIntegrationTest {

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = registerAndLogin("octocat");
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"demo","description":"a repo","visibility":"PUBLIC"}
                                """))
                .andExpect(status().isCreated());
    }

    private void commit(String message, String changes) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"%s","changes":[%s]}
                                """.formatted(message, changes)))
                .andExpect(status().isCreated());
    }

    private String tree(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree" + query))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void reportsTheCommitThatLastTouchedEachEntry() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"README.md","content":"one"},
                {"operation":"PUT","path":"src/App.java","content":"app"}
                """);
        commit("Edit the readme", """
                {"operation":"PUT","path":"README.md","content":"two"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree").param("withLastCommit", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].name").value("README.md"))
                .andExpect(jsonPath("$.entries[0].lastCommit.message").value("Edit the readme\n"))
                .andExpect(jsonPath("$.entries[0].lastCommit.authorName").value("octocat"))
                .andExpect(jsonPath("$.entries[0].lastCommit.shortSha").isNotEmpty())
                .andExpect(jsonPath("$.entries[0].lastCommit.timestamp").isNotEmpty())
                // The directory is credited with the commit that created what is
                // inside it, not with a change to the directory itself.
                .andExpect(jsonPath("$.entries[1].name").value("src"))
                .andExpect(jsonPath("$.entries[1].type").value("dir"))
                .andExpect(jsonPath("$.entries[1].lastCommit.message").value("Initial commit\n"));
    }

    /**
     * The compatibility promise. Not "the field is null" - the field is not
     * there at all, so a client parsing the old shape sees no difference.
     */
    @Test
    void omittingTheParameterLeavesTheResponseByteIdentical() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"README.md","content":"one"},
                {"operation":"PUT","path":"src/App.java","content":"app"}
                """);

        String absent = tree("");
        String explicitlyFalse = tree("?withLastCommit=false");

        assertThat(absent).isEqualTo(explicitlyFalse);
        assertThat(absent).doesNotContain("lastCommit");
        assertThat(absent).contains("\"name\":\"README.md\"", "\"type\":\"file\"", "\"mode\":\"100644\"");
    }

    @Test
    void askingForItAddsTheFieldAndNothingElse() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"README.md","content":"one"}
                """);

        String without = tree("");
        String with = tree("?withLastCommit=true");

        assertThat(with).contains("lastCommit");
        // Every field of the original shape survives unchanged.
        assertThat(objectMapper.readTree(without).get("entries").get(0).propertyNames().stream().toList())
                .containsExactlyInAnyOrder("name", "path", "type", "mode", "id");
        assertThat(objectMapper.readTree(with).get("entries").get(0).propertyNames().stream().toList())
                .containsExactlyInAnyOrder("name", "path", "type", "mode", "id", "lastCommit");
    }

    @Test
    void worksOnANestedDirectory() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"src/App.java","content":"app"},
                {"operation":"PUT","path":"src/util/Helper.java","content":"help"}
                """);
        commit("Extend the helper", """
                {"operation":"PUT","path":"src/util/Helper.java","content":"help more"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree")
                        .param("path", "src")
                        .param("withLastCommit", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src"))
                .andExpect(jsonPath("$.entries[0].name").value("App.java"))
                .andExpect(jsonPath("$.entries[0].lastCommit.message").value("Initial commit\n"))
                .andExpect(jsonPath("$.entries[1].name").value("util"))
                .andExpect(jsonPath("$.entries[1].lastCommit.message").value("Extend the helper\n"));
    }

    /**
     * An empty repository has no tree to list, so the endpoint answers 404 with
     * or without the flag - existing behaviour the client already handles with
     * its "this repository is empty" state. Asking for last commits must not
     * turn that into a server error.
     */
    @Test
    void anEmptyRepositoryIsStillJustNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree").param("withLastCommit", "true"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aSpecificRevisionIsRespected() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"README.md","content":"one"}
                """);
        String firstSha = objectMapper.readTree(
                        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("limit", "1"))
                                .andReturn().getResponse().getContentAsString())
                .get(0).get("sha").asString();

        commit("Edit the readme", """
                {"operation":"PUT","path":"README.md","content":"two"}
                """);

        // Asking about the older commit must not report the newer one.
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree")
                        .param("ref", firstSha)
                        .param("withLastCommit", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].lastCommit.message").value("Initial commit\n"));
    }

    /** Anonymous callers browse public repositories, so the field must reach them too. */
    @Test
    void anonymousCallersGetItAsWell() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"README.md","content":"one"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree").param("withLastCommit", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].lastCommit.sha").isNotEmpty());
    }

    @Test
    void aBadValueForTheFlagIsARequestProblemNotAServerOne() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"README.md","content":"one"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree").param("withLastCommit", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
