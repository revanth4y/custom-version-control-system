package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Browsing and writing repository contents over HTTP.
 */
class RepositoryContentApiIT extends AbstractIntegrationTest {

    private String token;

    private void createRepo(String tokenValue, String name, String visibility) throws Exception {
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(tokenValue))
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","description":"a repo","visibility":"%s"}
                                """.formatted(name, visibility)))
                .andExpect(status().isCreated());
    }

    private void commit(String tokenValue, String owner, String repo, String body) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/%s/%s/commits".formatted(owner, repo))
                        .header("Authorization", bearer(tokenValue))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());
    }

    private void seedRepository() throws Exception {
        token = registerAndLogin("octocat");
        createRepo(token, "demo", "PUBLIC");

        commit(token, "octocat", "demo", """
                {"branch":"main","message":"Initial commit","changes":[
                  {"operation":"PUT","path":"README.md","content":"# Demo\\n"},
                  {"operation":"PUT","path":"src/App.java","content":"class App {}\\n"},
                  {"operation":"PUT","path":"src/deep/Util.java","content":"class Util {}\\n"}
                ]}
                """);
    }

    @Nested
    @DisplayName("repository creation")
    class Creation {

        @Test
        void initialisesVersionControlStorageWithHeadOnMain() throws Exception {
            token = registerAndLogin("octocat");
            createRepo(token, "demo", "PUBLIC");

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/head"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.branch").value("main"))
                    .andExpect(jsonPath("$.detached").value(false))
                    // The branch itself appears with the first commit.
                    .andExpect(jsonPath("$.commit").doesNotExist());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void repositoriesRemainIsolatedFromOneAnother() throws Exception {
            token = registerAndLogin("octocat");
            createRepo(token, "alpha", "PUBLIC");
            createRepo(token, "beta", "PUBLIC");

            commit(token, "octocat", "alpha", """
                    {"branch":"main","message":"Alpha only","changes":[
                      {"operation":"PUT","path":"alpha.txt","content":"alpha\\n"}]}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/alpha/tree"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries.length()").value(1));

            // beta has no commits at all, so it has nothing to browse.
            mockMvc.perform(get("/api/v1/repositories/octocat/beta/tree"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/octocat/beta/commits"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("browsing")
    class Browsing {

        @Test
        void listsTheRepositoryRoot() throws Exception {
            seedRepository();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries.length()").value(2))
                    .andExpect(jsonPath("$.entries[0].name").value("README.md"))
                    .andExpect(jsonPath("$.entries[0].type").value("file"))
                    .andExpect(jsonPath("$.entries[1].name").value("src"))
                    .andExpect(jsonPath("$.entries[1].type").value("dir"));
        }

        @Test
        void listsANestedDirectory() throws Exception {
            seedRepository();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree").param("path", "src"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[0].path").value("src/App.java"))
                    .andExpect(jsonPath("$.entries[1].name").value("deep"));
        }

        @Test
        void missingDirectoryIsNotFound() throws Exception {
            seedRepository();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree").param("path", "nope"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("reading files")
    class Reading {

        @Test
        void readsATextFileAsUtf8() throws Exception {
            seedRepository();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "README.md"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.binary").value(false))
                    .andExpect(jsonPath("$.encoding").value("utf-8"))
                    .andExpect(jsonPath("$.content").value("# Demo\n"))
                    .andExpect(jsonPath("$.mode").value("100644"));
        }

        @Test
        void readsBinaryContentAsBase64WithoutCorruptingIt() throws Exception {
            token = registerAndLogin("octocat");
            createRepo(token, "demo", "PUBLIC");

            // Every byte value, including NUL and sequences that are not valid UTF-8.
            byte[] binary = new byte[256];
            for (int i = 0; i < binary.length; i++) {
                binary[i] = (byte) i;
            }
            String encoded = Base64.getEncoder().encodeToString(binary);

            commit(token, "octocat", "demo", """
                    {"branch":"main","message":"Add binary","changes":[
                      {"operation":"PUT","path":"data.bin","content":"%s","encoding":"base64"}]}
                    """.formatted(encoded));

            String response = mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob")
                            .param("path", "data.bin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.binary").value(true))
                    .andExpect(jsonPath("$.encoding").value("base64"))
                    .andExpect(jsonPath("$.size").value(256))
                    .andReturn().getResponse().getContentAsString();

            byte[] returned = Base64.getDecoder().decode(
                    objectMapper.readTree(response).get("content").asString());

            // The decisive assertion: the exact bytes survived the round trip.
            assertThat(returned).isEqualTo(binary);
        }

        @Test
        void readsAnEmptyFile() throws Exception {
            token = registerAndLogin("octocat");
            createRepo(token, "demo", "PUBLIC");
            commit(token, "octocat", "demo", """
                    {"branch":"main","message":"Add empty","changes":[
                      {"operation":"PUT","path":"empty.txt","content":""}]}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "empty.txt"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(0))
                    .andExpect(jsonPath("$.binary").value(false));
        }

        @Test
        void readingADirectoryOrAMissingFileIsNotFound() throws Exception {
            seedRepository();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "src"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "ghost.txt"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void readsFromAnEarlierRevision() throws Exception {
            seedRepository();
            commit(token, "octocat", "demo", """
                    {"branch":"main","message":"Update readme","changes":[
                      {"operation":"PUT","path":"README.md","content":"# Demo v2\\n"}]}
                    """);

            String history = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                    .andReturn().getResponse().getContentAsString();
            String firstSha = objectMapper.readTree(history).get(1).get("sha").asString();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob")
                            .param("ref", firstSha).param("path", "README.md"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").value("# Demo\n"));
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {

        @Test
        void writesASingleFileThroughTheContentsEndpoint() throws Exception {
            token = registerAndLogin("octocat");
            createRepo(token, "demo", "PUBLIC");

            mockMvc.perform(put("/api/v1/repositories/octocat/demo/contents")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","path":"notes.md","content":"# Notes\\n",
                                     "message":"Add notes"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("Add notes\n"))
                    .andExpect(jsonPath("$.sha").isNotEmpty());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "notes.md"))
                    .andExpect(jsonPath("$.content").value("# Notes\n"));
        }

        @Test
        void recordsMultipleFilesInOneCommit() throws Exception {
            seedRepository();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree").param("path", "src/deep"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[0].path").value("src/deep/Util.java"));
        }

        @Test
        void appliesPutsAndDeletesTogether() throws Exception {
            seedRepository();

            commit(token, "octocat", "demo", """
                    {"branch":"main","message":"Mixed change","changes":[
                      {"operation":"PUT","path":"README.md","content":"# Rewritten\\n"},
                      {"operation":"DELETE","path":"src/App.java"},
                      {"operation":"PUT","path":"CHANGELOG.md","content":"changes\\n"}]}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "README.md"))
                    .andExpect(jsonPath("$.content").value("# Rewritten\n"));
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "src/App.java"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "CHANGELOG.md"))
                    .andExpect(status().isOk());
        }

        @Test
        void preservesExecutableMode() throws Exception {
            token = registerAndLogin("octocat");
            createRepo(token, "demo", "PUBLIC");
            commit(token, "octocat", "demo", """
                    {"branch":"main","message":"Add script","changes":[
                      {"operation":"PUT","path":"run.sh","content":"#!/bin/sh\\n","mode":"100755"}]}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "run.sh"))
                    .andExpect(jsonPath("$.mode").value("100755"));
        }

        @Test
        void rejectsACommitThatChangesNothing() throws Exception {
            seedRepository();

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"No-op","changes":[
                                      {"operation":"PUT","path":"README.md","content":"# Demo\\n"}]}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Nothing to commit")));
        }

        @Test
        void rejectsDeletingAPathThatDoesNotExist() throws Exception {
            seedRepository();

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Bad delete","changes":[
                                      {"operation":"DELETE","path":"ghost.txt"}]}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsPathTraversal() throws Exception {
            seedRepository();

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Escape","changes":[
                                      {"operation":"PUT","path":"../escape.txt","content":"x\\n"}]}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsAnInvalidChangeSet() throws Exception {
            seedRepository();

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Bad","changes":[]}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Bad","changes":[
                                      {"operation":"RENAME","path":"a.txt"}]}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }
}
