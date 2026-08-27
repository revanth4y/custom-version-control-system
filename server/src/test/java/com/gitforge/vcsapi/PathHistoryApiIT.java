package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * History narrowed to one path, through the whole stack.
 *
 * <p>The endpoint refused this parameter until now, so what matters is not only
 * that a filtered listing comes back but that the filter is really applied: a
 * test that only counted results would pass just as well against the old
 * behaviour of ignoring the parameter and returning everything.
 */
class PathHistoryApiIT extends AbstractIntegrationTest {

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

        commit("Initial commit", """
                {"operation":"PUT","path":"README.md","content":"# Demo\\n"}
                """);
        commit("Add the source", """
                {"operation":"PUT","path":"src/App.java","content":"class App {}\\n"}
                """);
        commit("Document it", """
                {"operation":"PUT","path":"README.md","content":"# Demo\\n\\nNotes.\\n"}
                """);
        commit("Refine the source", """
                {"operation":"PUT","path":"src/App.java","content":"class App { }\\n"}
                """);
    }

    private void commit(String message, String change) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"%s","changes":[%s]}
                                """.formatted(message, change)))
                .andExpect(status().isCreated());
    }

    @Nested
    @DisplayName("filtering")
    class Filtering {

        @Test
        void returnsOnlyTheCommitsThatTouchedTheFile() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("path", "README.md"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].message").value("Document it\n"))
                    .andExpect(jsonPath("$[1].message").value("Initial commit\n"));
        }

        @Test
        void aDifferentFileHasADifferentHistory() throws Exception {
            // Proves the parameter is applied rather than ignored: the same
            // request with a different path must not answer with the same list.
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("path", "src/App.java"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].message").value("Refine the source\n"))
                    .andExpect(jsonPath("$[1].message").value("Add the source\n"));
        }

        @Test
        void aDirectoryCoversWhatIsBeneathIt() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("path", "src"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].message").value("Refine the source\n"));
        }

        @Test
        void surroundingSlashesNameTheSameDirectory() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("path", "/src/"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }

        @Test
        void aBlankPathIsTheWholeHistory() throws Exception {
            // The root is touched by every commit, so this is not a filter that
            // matches nothing — and it is not an error either.
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("path", ""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(4));
        }

        @Test
        void aPathThatWasNeverTouchedIsAnEmptyList() throws Exception {
            // Not a 404: the repository and the revision both exist, and "no
            // commits touched this" is an answer rather than a missing resource.
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("path", "does/not/exist.txt"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void aDeletedFileKeepsItsHistory() throws Exception {
            commit("Remove the source", """
                    {"operation":"DELETE","path":"src/App.java"}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("path", "src/App.java"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].message").value("Remove the source\n"));
        }
    }

    @Nested
    @DisplayName("combined with the other parameters")
    class Combinations {

        @Test
        void limitCapsAFilteredListing() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("path", "README.md")
                            .param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].message").value("Document it\n"));
        }

        @Test
        void refAndPathNarrowTogether() throws Exception {
            String history = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                    .andReturn().getResponse().getContentAsString();
            // The third commit from the tip is "Add the source"; at that point
            // README had been written once, not twice.
            String earlier = objectMapper.readTree(history).get(2).get("sha").asString();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("ref", earlier)
                            .param("path", "README.md"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].message").value("Initial commit\n"));
        }

        @Test
        void anUnknownRevisionIsAnEmptyListRatherThanAnError() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("ref", "no-such-branch")
                            .param("path", "README.md"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void aPrivateRepositoryStillHidesItsHistory() throws Exception {
            // Filtering must not become a way around the read rules.
            String other = registerAndLogin("hubot");
            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(other))
                            .contentType("application/json")
                            .content("""
                                    {"name":"secret","description":null,"visibility":"PRIVATE"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/hubot/secret/commits")
                            .param("path", "README.md"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("commit metadata")
    class Metadata {

        @Test
        void everyCommitCarriesBothSignatures() throws Exception {
            /* The stored commit has an author and a committer. Through this API
               they are written the same, which is worth asserting: if that ever
               changes the interface has to stop treating one as the other. */
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("limit", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].authorName").value("octocat"))
                    .andExpect(jsonPath("$[0].authorEmail").isNotEmpty())
                    .andExpect(jsonPath("$[0].timestamp").isNotEmpty())
                    .andExpect(jsonPath("$[0].committerName").value("octocat"))
                    .andExpect(jsonPath("$[0].committerEmail").isNotEmpty())
                    .andExpect(jsonPath("$[0].committerTimestamp").isNotEmpty());
        }

        @Test
        void commitDetailCarriesThemToo() throws Exception {
            String history = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                    .andReturn().getResponse().getContentAsString();
            String sha = objectMapper.readTree(history).get(0).get("sha").asString();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + sha))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit.committerName").value("octocat"))
                    .andExpect(jsonPath("$.commit.committerTimestamp").isNotEmpty());
        }
    }
}
