package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepositoryMergeApiIT extends AbstractIntegrationTest {

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

        commit("main", "Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"a\\n"},
                {"operation":"PUT","path":"b.txt","content":"b\\n"}
                """);
        branch("feature", "main");
    }

    private void commit(String branch, String message, String changes) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"%s","message":"%s","changes":[%s]}
                                """.formatted(branch, message, changes)))
                .andExpect(status().isCreated());
    }

    private void branch(String name, String startPoint) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","startPoint":"%s"}
                                """.formatted(name, startPoint)))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions merge(String ours, String theirs) throws Exception {
        return mockMvc.perform(post("/api/v1/repositories/octocat/demo/merge")
                .header("Authorization", bearer(token))
                .contentType("application/json")
                .content("""
                        {"ourBranch":"%s","theirBranch":"%s"}
                        """.formatted(ours, theirs)));
    }

    private String tipOf(String branch) throws Exception {
        String body = mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                .andReturn().getResponse().getContentAsString();

        for (var node : objectMapper.readTree(body)) {
            if (node.get("name").asString().equals(branch)) {
                return node.get("commit").asString();
            }
        }
        throw new AssertionError("No such branch: " + branch);
    }

    @Test
    void reportsAlreadyUpToDateWhenTheirBranchIsContained() throws Exception {
        commit("main", "Move ahead", """
                {"operation":"PUT","path":"c.txt","content":"c\\n"}
                """);

        merge("main", "feature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_UP_TO_DATE"))
                .andExpect(jsonPath("$.mergeCommit").doesNotExist());
    }

    @Test
    void fastForwardsWhenOurBranchIsBehind() throws Exception {
        commit("feature", "Feature work", """
                {"operation":"PUT","path":"feature.txt","content":"feature\\n"}
                """);
        String featureTip = tipOf("feature");

        merge("main", "feature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FAST_FORWARDED"))
                .andExpect(jsonPath("$.head").value(featureTip))
                // A fast-forward reconciles nothing, so it creates no commit.
                .andExpect(jsonPath("$.mergeCommit").doesNotExist());

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "feature.txt"))
                .andExpect(status().isOk());
    }

    @Test
    void mergesDivergentBranchesCleanly() throws Exception {
        commit("main", "Main edit", """
                {"operation":"PUT","path":"a.txt","content":"main edited a\\n"}
                """);
        commit("feature", "Feature edit", """
                {"operation":"PUT","path":"b.txt","content":"feature edited b\\n"}
                """);

        String ours = tipOf("main");
        String theirs = tipOf("feature");

        String response = merge("main", "feature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MERGED"))
                .andExpect(jsonPath("$.mergeCommit").isNotEmpty())
                .andExpect(jsonPath("$.tree").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String mergeSha = objectMapper.readTree(response).get("mergeCommit").asString();

        // Both sides' work is present.
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "a.txt"))
                .andExpect(jsonPath("$.content").value("main edited a\n"));
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "b.txt"))
                .andExpect(jsonPath("$.content").value("feature edited b\n"));

        // Parent order is identity: ours first, theirs second.
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + mergeSha))
                .andExpect(jsonPath("$.commit.merge").value(true))
                .andExpect(jsonPath("$.commit.parents.length()").value(2))
                .andExpect(jsonPath("$.commit.parents[0]").value(ours))
                .andExpect(jsonPath("$.commit.parents[1]").value(theirs));
    }

    @Test
    void usesTheSuppliedMergeMessage() throws Exception {
        commit("main", "Main edit", """
                {"operation":"PUT","path":"a.txt","content":"main\\n"}
                """);
        commit("feature", "Feature edit", """
                {"operation":"PUT","path":"b.txt","content":"feature\\n"}
                """);

        mockMvc.perform(post("/api/v1/repositories/octocat/demo/merge")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"ourBranch":"main","theirBranch":"feature","message":"Custom merge"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("limit", "1"))
                .andExpect(jsonPath("$[0].message").value("Custom merge\n"));
    }

    @Test
    void returnsConflictWithTheOffendingPaths() throws Exception {
        commit("main", "Main edit", """
                {"operation":"PUT","path":"a.txt","content":"main version\\n"}
                """);
        commit("feature", "Feature edit", """
                {"operation":"PUT","path":"a.txt","content":"feature version\\n"}
                """);

        merge("main", "feature")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("CONFLICTED"))
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].path").value("a.txt"))
                .andExpect(jsonPath("$.conflicts[0].kind").value("CONTENT"))
                .andExpect(jsonPath("$.conflicts[0].ours.id").isNotEmpty())
                .andExpect(jsonPath("$.conflicts[0].theirs.id").isNotEmpty())
                .andExpect(jsonPath("$.mergeCommit").doesNotExist());
    }

    @Test
    void aConflictLeavesBothBranchesWhereTheyWere() throws Exception {
        commit("main", "Main edit", """
                {"operation":"PUT","path":"a.txt","content":"main version\\n"}
                """);
        commit("feature", "Feature edit", """
                {"operation":"PUT","path":"a.txt","content":"feature version\\n"}
                """);

        String oursBefore = tipOf("main");
        String theirsBefore = tipOf("feature");

        merge("main", "feature").andExpect(status().isConflict());

        assertBranchUnchanged("main", oursBefore);
        assertBranchUnchanged("feature", theirsBefore);

        // Our content is untouched too.
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "a.txt"))
                .andExpect(jsonPath("$.content").value("main version\n"));
    }

    @Test
    void reportsWhatWouldHaveMergedCleanlyAlongsideConflicts() throws Exception {
        commit("main", "Main edit", """
                {"operation":"PUT","path":"a.txt","content":"main version\\n"}
                """);
        commit("feature", "Feature edits", """
                {"operation":"PUT","path":"a.txt","content":"feature version\\n"},
                {"operation":"PUT","path":"b.txt","content":"feature only\\n"}
                """);

        merge("main", "feature")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0].path").value("a.txt"))
                .andExpect(jsonPath("$.cleanlyMerged.length()").value(1))
                .andExpect(jsonPath("$.cleanlyMerged[0].path").value("b.txt"));
    }

    @Test
    void mergingAnAbsentBranchIsNotFound() throws Exception {
        merge("main", "ghost").andExpect(status().isNotFound());
    }

    @Test
    void mergeRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/merge")
                        .contentType("application/json")
                        .content("""
                                {"ourBranch":"main","theirBranch":"feature"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private void assertBranchUnchanged(String branch, String expected) throws Exception {
        org.assertj.core.api.Assertions.assertThat(tipOf(branch)).isEqualTo(expected);
    }
}
