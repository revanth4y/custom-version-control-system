package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepositoryBranchApiIT extends AbstractIntegrationTest {

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

        mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"Initial commit","changes":[
                                  {"operation":"PUT","path":"README.md","content":"# Demo\\n"}]}
                                """))
                .andExpect(status().isCreated());
    }

    @Nested
    @DisplayName("branches")
    class Branches {

        @Test
        void listsBranchesAndMarksTheOneHeadNames() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("main"))
                    .andExpect(jsonPath("$[0].head").value(true))
                    .andExpect(jsonPath("$[0].commit").isNotEmpty());
        }

        @Test
        void carriesTheTipCommitOfEachBranch() throws Exception {
            String json = mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andExpect(status().isOk())
                    // The engine normalises a commit message to exactly one
                    // trailing newline, and the API passes it through unaltered.
                    .andExpect(jsonPath("$[0].tip.message").value("Initial commit\n"))
                    .andExpect(jsonPath("$[0].tip.authorName").value("octocat"))
                    .andExpect(jsonPath("$[0].tip.timestamp").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();

            var branch = objectMapper.readTree(json).get(0);
            String commit = branch.get("commit").asString();

            // The tip must describe the commit the reference names, not merely
            // some commit: a listing that pairs a branch with another branch's
            // message would look perfectly plausible.
            assertThat(branch.get("tip").get("sha").asString()).isEqualTo(commit);
            assertThat(branch.get("tip").get("shortSha").asString()).isEqualTo(commit.substring(0, 7));
        }

        /**
         * A branch name may contain slashes. It travels as a query parameter for
         * exactly that reason, so the nested cases are worth pinning: they are
         * the ones a path variable would have mangled.
         */
        @Test
        void handlesBranchNamesContainingSlashes() throws Exception {
            for (String branch : List.of("feature/login", "release/1.0", "bugfix/auth/token")) {
                mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                                .header("Authorization", bearer(token))
                                .contentType("application/json")
                                .content("""
                                        {"name":"%s","startPoint":"main"}
                                        """.formatted(branch)))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.name").value(branch))
                        .andExpect(jsonPath("$.tip.message").value("Initial commit\n"));
            }

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andExpect(jsonPath("$.length()").value(4))
                    .andExpect(jsonPath("$[?(@.name == 'bugfix/auth/token')]").exists());

            // Pointing HEAD at a nested name, and reading it back, is the round
            // trip a slash would break if the name were ever put in a path.
            mockMvc.perform(put("/api/v1/repositories/octocat/demo/head")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"bugfix/auth/token"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.branch").value("bugfix/auth/token"));

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/branches")
                            .param("name", "release/1.0")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andExpect(jsonPath("$.length()").value(3));
        }

        @Test
        void createsABranchFromAStartPoint() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"feature/login","startPoint":"main"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("feature/login"));

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        void rejectsADuplicateBranch() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"main","startPoint":"main"}
                                    """))
                    .andExpect(status().isConflict());
        }

        @Test
        void rejectsAnUnresolvableStartPoint() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"feature","startPoint":"no-such-branch"}
                                    """))
                    .andExpect(status().isNotFound());
        }

        @Test
        void rejectsAnUnsafeBranchName() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"../../escape","startPoint":"main"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void deletesABranch() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"feature","startPoint":"main"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/branches")
                            .param("name", "feature")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        void deletingAnAbsentBranchIsNotFound() throws Exception {
            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/branches")
                            .param("name", "ghost")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void refusesToDeleteTheCheckedOutBranch() throws Exception {
            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/branches")
                            .param("name", "main")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("HEAD")
    class HeadEndpoint {

        @Test
        void reportsTheCurrentBranchAndCommit() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/head"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.branch").value("main"))
                    .andExpect(jsonPath("$.detached").value(false))
                    .andExpect(jsonPath("$.commit").isNotEmpty());
        }

        @Test
        void pointsHeadAtAnotherBranch() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"feature","startPoint":"main"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(put("/api/v1/repositories/octocat/demo/head")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"feature"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.branch").value("feature"));

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/head"))
                    .andExpect(jsonPath("$.branch").value("feature"));
        }

        @Test
        void refusesToPointHeadAtAnAbsentBranch() throws Exception {
            mockMvc.perform(put("/api/v1/repositories/octocat/demo/head")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"ghost"}
                                    """))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("commit history")
    class History {

        @Test
        void listsCommitsNewestFirst() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Second commit","changes":[
                                      {"operation":"PUT","path":"b.txt","content":"b\\n"}]}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].message").value("Second commit\n"))
                    .andExpect(jsonPath("$[1].message").value("Initial commit\n"))
                    .andExpect(jsonPath("$[0].shortSha").isNotEmpty())
                    .andExpect(jsonPath("$[0].authorName").value("octocat"));
        }

        @Test
        void respectsTheLimit() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Second commit","changes":[
                                      {"operation":"PUT","path":"b.txt","content":"b\\n"}]}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("limit", "1"))
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        void showsCommitDetailWithItsChanges() throws Exception {
            String history = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                    .andReturn().getResponse().getContentAsString();
            String sha = objectMapper.readTree(history).get(0).get("sha").asString();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + sha))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit.sha").value(sha))
                    .andExpect(jsonPath("$.commit.merge").value(false))
                    // An initial commit is measured against the empty tree.
                    .andExpect(jsonPath("$.changes.added").value(1))
                    .andExpect(jsonPath("$.changes.changes[0].type").value("ADDED"))
                    .andExpect(jsonPath("$.changes.changes[0].path").value("README.md"));
        }

        @Test
        void anUnknownCommitIsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + "0".repeat(40)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void aMalformedCommitIdIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/not-a-sha"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void comparesTwoRevisions() throws Exception {
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
                                      {"operation":"PUT","path":"feature.txt","content":"feature\\n"},
                                      {"operation":"PUT","path":"README.md","content":"# Changed\\n"}]}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/compare")
                            .param("base", "main").param("head", "feature"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changes.added").value(1))
                    .andExpect(jsonPath("$.changes.modified").value(1));
        }

        @Test
        void comparingAnUnknownRevisionIsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/compare")
                            .param("base", "main").param("head", "ghost"))
                    .andExpect(status().isNotFound());
        }
    }
}
