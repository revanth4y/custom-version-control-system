package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import com.gitforge.user.User;
import com.gitforge.user.UserService;
import com.gitforge.vcs.object.Blob;
import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Abbreviated object ids, through the whole stack.
 *
 * <p>Every case here has a full-length counterpart that must keep working: the
 * point of the release is that a short id starts resolving, not that a long one
 * stops. The regression assertions are as important as the new ones.
 */
class AbbreviatedIdApiIT extends AbstractIntegrationTest {

    private String token;
    private String headSha;

    @Autowired
    private VcsRepositoryProvider repositories;

    @Autowired
    private UserService users;

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

        commit("Initial commit", "README.md", "# Demo\\n");
        commit("Add the source", "src/App.java", "class App {}\\n");

        headSha = latestSha();
    }

    private void commit(String message, String path, String content) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"%s","changes":[
                                  {"operation":"PUT","path":"%s","content":"%s"}]}
                                """.formatted(message, path, content)))
                .andExpect(status().isCreated());
    }

    private String latestSha() throws Exception {
        String history = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(history).get(0).get("sha").asString();
    }

    /**
     * Writes objects until one shares {@code prefix}, so ambiguity is constructed
     * rather than hoped for.
     *
     * <p>Below the API on purpose: the point is two objects colliding, and how
     * they got there does not matter to the resolver.
     */
    private ObjectId forceCollision(String prefix) {
        User owner = users.requireByUsername("octocat");
        var store = repositories.forWrite("octocat", "demo", owner).objects();

        for (int i = 0; i < 1_000_000; i++) {
            Blob candidate = new Blob(("collision-" + i).getBytes(StandardCharsets.UTF_8));
            if (candidate.id().toHex().startsWith(prefix)) {
                return store.write(candidate);
            }
        }
        throw new IllegalStateException("No object found with prefix " + prefix);
    }

    @Nested
    @DisplayName("commit detail")
    class Detail {

        @Test
        void theFullIdStillWorks() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + headSha))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit.sha").value(headSha));
        }

        @Test
        void sevenCharactersResolveToTheSameCommit() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + headSha.substring(0, 7)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit.sha").value(headSha));
        }

        @Test
        void fourCharactersAreEnough() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + headSha.substring(0, 4)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit.sha").value(headSha));
        }

        @Test
        void capitalsResolveTheSameWay() throws Exception {
            String upper = headSha.substring(0, 8).toUpperCase(java.util.Locale.ROOT);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + upper))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit.sha").value(headSha));
        }

        @Test
        void threeCharactersAreRefused() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + headSha.substring(0, 3)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        @Test
        void somethingThatIsNotHexadecimalIsRefused() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/not-a-sha"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        @Test
        void anAbbreviationMatchingNothingIsNotFound() throws Exception {
            // Not a 400: the request is well formed, the object simply is not there.
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/ffffffff"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        void aFullIdMatchingNothingIsStillNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + "0".repeat(40)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void anAmbiguousAbbreviationIsAConflictNamingTheCandidates() throws Exception {
            String shared = headSha.substring(0, 4);
            forceCollision(shared);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/" + shared))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"))
                    .andExpect(jsonPath("$.message").value(containsString("ambiguous")))
                    .andExpect(jsonPath("$.message").value(containsString(shared)))
                    // The count is stated, so lengthening the prefix is obviously
                    // the fix rather than a guess.
                    .andExpect(jsonPath("$.message").value(containsString("2 objects match")));
        }

        @Test
        void theCommitDiffAcceptsAnAbbreviation() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/"
                            + headSha.substring(0, 7) + "/diff"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.files").isArray());
        }
    }

    @Nested
    @DisplayName("as a revision")
    class AsRevision {

        @Test
        void historyAcceptsAnAbbreviation() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("ref", headSha.substring(0, 7)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sha").value(headSha));
        }

        @Test
        void pathFilteredHistoryAcceptsAnAbbreviation() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("ref", headSha.substring(0, 7))
                            .param("path", "README.md"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        void theTreeAcceptsAnAbbreviation() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree")
                            .param("ref", headSha.substring(0, 7)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries").isArray());
        }

        @Test
        void aBlobAcceptsAnAbbreviation() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob")
                            .param("ref", headSha.substring(0, 7))
                            .param("path", "README.md"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.path").value("README.md"));
        }

        @Test
        void compareAcceptsAbbreviationsOnBothSides() throws Exception {
            String history = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                    .andReturn().getResponse().getContentAsString();
            String older = objectMapper.readTree(history).get(1).get("sha").asString();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/compare")
                            .param("base", older.substring(0, 7))
                            .param("head", headSha.substring(0, 7)))
                    .andExpect(status().isOk());
        }

        @Test
        void diffAcceptsAbbreviations() throws Exception {
            String history = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                    .andReturn().getResponse().getContentAsString();
            String older = objectMapper.readTree(history).get(1).get("sha").asString();

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/diff")
                            .param("base", older.substring(0, 7))
                            .param("head", headSha.substring(0, 7)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.files").isArray());
        }

        @Test
        void aBranchCanStartFromAnAbbreviation() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"from-short","startPoint":"%s"}
                                    """.formatted(headSha.substring(0, 7))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.commit").value(headSha));
        }

        @Test
        void aBranchNamedLikeAnAbbreviationStillWins() throws Exception {
            /* Precedence, end to end. A branch called "abcd" resolves to what it
               points at, not to the object whose id starts "abcd". */
            String name = headSha.substring(0, 6);
            String older = objectMapper.readTree(
                            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                                    .andReturn().getResponse().getContentAsString())
                    .get(1).get("sha").asString();

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"%s","startPoint":"%s"}
                                    """.formatted(name, older)))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("ref", name))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sha").value(older));
        }

        @Test
        void headIsUnchanged() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("ref", "HEAD"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sha").value(headSha));
        }
    }

    @Nested
    @DisplayName("unresolvable revisions")
    class Unresolvable {

        @Test
        void aPopulatedRepositoryRefusesAnUnknownRef() throws Exception {
            /* This answered 200 with an empty list until V2.0.4, which reads
               exactly like a branch nobody has committed to. */
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits")
                            .param("ref", "no-such-branch"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        void anEmptyRepositoryStillAnswersWithAnEmptyHistory() throws Exception {
            /* The exception that matters. An empty repository's HEAD names a
               branch that does not exist yet — that is what empty means — and
               the interface asks for exactly that branch by name when it opens
               one. A 404 here would turn the ordinary empty state into an error. */
            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"fresh","description":null,"visibility":"PUBLIC"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/fresh/commits").param("ref", "main"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));

            mockMvc.perform(get("/api/v1/repositories/octocat/fresh/commits"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));

            mockMvc.perform(get("/api/v1/repositories/octocat/fresh/commits").param("ref", "HEAD"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Nested
    @DisplayName("authorization comes first")
    class Authorization {

        @Test
        void aRepositoryThatDoesNotExistIsMissingBeforeAnythingIsResolved() throws Exception {
            // The read check runs first, so nothing about the id is examined —
            // valid, too short, or nonsense all answer the same way.
            mockMvc.perform(get("/api/v1/repositories/octocat/nope/commits/abcd"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/octocat/nope/commits/abc"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/octocat/nope/commits")
                            .param("ref", "abcd"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void aPrivateRepositoryIsMissingEvenForAnAmbiguousPrefix() throws Exception {
            /* Resolution must never be what decides whether a caller may look. A
               409 here would confirm the repository exists, and that two objects
               inside it collide. */
            String other = registerAndLogin("hubot");
            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(other))
                            .contentType("application/json")
                            .content("""
                                    {"name":"secret","description":null,"visibility":"PRIVATE"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/hubot/secret/commits/abcd"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));

            mockMvc.perform(get("/api/v1/repositories/hubot/secret/commits").param("ref", "abcd"))
                    .andExpect(status().isNotFound());
        }
    }
}
