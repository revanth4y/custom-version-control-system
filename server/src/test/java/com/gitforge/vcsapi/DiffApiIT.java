package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiffApiIT extends AbstractIntegrationTest {

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

    private String latestSha() throws Exception {
        String history = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("limit", "1"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(history).get(0).get("sha").asString();
    }

    @Test
    void aCommitDiffCarriesRealHunksWithLineNumbers() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"one\\ntwo\\nthree\\n"}
                """);
        commit("Edit", """
                {"operation":"PUT","path":"a.txt","content":"one\\nTWO\\nthree\\n"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/%s/diff".formatted(latestSha())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filesChanged").value(1))
                .andExpect(jsonPath("$.totalAdditions").value(1))
                .andExpect(jsonPath("$.totalDeletions").value(1))
                .andExpect(jsonPath("$.files[0].path").value("a.txt"))
                .andExpect(jsonPath("$.files[0].status").value("MODIFIED"))
                .andExpect(jsonPath("$.files[0].binary").value(false))
                .andExpect(jsonPath("$.files[0].hunks.length()").value(1))
                .andExpect(jsonPath("$.files[0].hunks[0].header").value("@@ -1,3 +1,3 @@"))
                .andExpect(jsonPath("$.files[0].hunks[0].lines.length()").value(4))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[0].type").value("CONTEXT"))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[0].content").value("one"))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[1].type").value("REMOVED"))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[1].oldNumber").value(2))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[1].newNumber").doesNotExist())
                .andExpect(jsonPath("$.files[0].hunks[0].lines[2].type").value("ADDED"))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[2].newNumber").value(2));
    }

    @Test
    void anInitialCommitShowsEveryFileAsAdded() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"one\\ntwo\\n"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/%s/diff".formatted(latestSha())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].status").value("ADDED"))
                .andExpect(jsonPath("$.files[0].additions").value(2))
                .andExpect(jsonPath("$.files[0].oldBlob").doesNotExist());
    }

    @Test
    void binaryFilesAreFlaggedRatherThanLineDiffed() throws Exception {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        commit("Add binary", """
                {"operation":"PUT","path":"data.bin","content":"%s","encoding":"base64"}
                """.formatted(Base64.getEncoder().encodeToString(binary)));

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/%s/diff".formatted(latestSha())))
                .andExpect(jsonPath("$.files[0].binary").value(true))
                .andExpect(jsonPath("$.files[0].hunks.length()").value(0));
    }

    @Test
    void comparesTwoBranchesWithHunks() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"base\\n"}
                """);
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"feature","startPoint":"main"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"feature","message":"Feature work","changes":[
                                  {"operation":"PUT","path":"a.txt","content":"changed\\n"},
                                  {"operation":"PUT","path":"new.txt","content":"new\\n"}]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/diff")
                        .param("base", "main").param("head", "feature"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filesChanged").value(2))
                .andExpect(jsonPath("$.files[0].path").value("a.txt"))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[0].content").value("base"))
                .andExpect(jsonPath("$.files[1].path").value("new.txt"))
                .andExpect(jsonPath("$.files[1].status").value("ADDED"));
    }

    @Test
    void aPathFilterNarrowsTheDiff() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"a\\n"},
                {"operation":"PUT","path":"b.txt","content":"b\\n"}
                """);
        commit("Edit both", """
                {"operation":"PUT","path":"a.txt","content":"A\\n"},
                {"operation":"PUT","path":"b.txt","content":"B\\n"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/%s/diff".formatted(latestSha()))
                        .param("path", "a.txt"))
                .andExpect(jsonPath("$.filesChanged").value(1))
                .andExpect(jsonPath("$.files[0].path").value("a.txt"));
    }

    @Test
    void identicalRevisionsDifferInNothing() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"a\\n"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/diff")
                        .param("base", "main").param("head", "main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filesChanged").value(0))
                .andExpect(jsonPath("$.totalAdditions").value(0));
    }

    @Test
    void theStructuralCompareEndpointStillWorks() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"a\\n"}
                """);
        commit("Edit", """
                {"operation":"PUT","path":"a.txt","content":"A\\n"}
                """);

        // Unchanged by the addition of line-level diffs.
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/compare")
                        .param("base", "HEAD").param("head", "HEAD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.changes.length()").value(0));
    }

    @Test
    void unknownRevisionsAndMalformedShasAreRejected() throws Exception {
        commit("Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"a\\n"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/diff")
                        .param("base", "main").param("head", "ghost"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/not-a-sha/diff"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/%s/diff".formatted("0".repeat(40))))
                .andExpect(status().isNotFound());
    }

    @Test
    void diffsOnAPrivateRepositoryAreHiddenFromStrangers() throws Exception {
        String stranger = registerAndLogin("stranger");
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"secret","description":"private","visibility":"PRIVATE"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/repositories/octocat/secret/diff")
                        .param("base", "main").param("head", "main")
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }
}
