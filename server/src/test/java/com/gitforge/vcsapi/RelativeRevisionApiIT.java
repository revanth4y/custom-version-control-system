package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relative revisions, through the whole stack.
 *
 * <p>Two things are being checked that the engine tests cannot: that the new
 * syntax is accepted everywhere a revision is already accepted, and that each
 * way of failing arrives as the status the API contract already assigns it —
 * malformed as 400, unresolvable as 404, ambiguous as the 409 it has always
 * been.
 */
class RelativeRevisionApiIT extends AbstractIntegrationTest {

    private static final String REPO = "/api/v1/repositories/octocat/demo";

    private String token;
    private String headSha;
    private String parentSha;

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
        commit("Second commit", "second.txt", "second\\n");
        commit("Third commit", "third.txt", "third\\n");

        headSha = shaAt(0);
        parentSha = shaAt(1);
    }

    private void commit(String message, String path, String content) throws Exception {
        mockMvc.perform(post(REPO + "/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"%s","changes":[
                                  {"operation":"PUT","path":"%s","content":"%s"}
                                ]}
                                """.formatted(message, path, content)))
                .andExpect(status().isCreated());
    }

    /** The commit that many steps back from the tip, newest first. */
    private String shaAt(int index) throws Exception {
        String history = mockMvc.perform(get(REPO + "/commits"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(history).get(index).get("sha").asString();
    }

    @Nested
    @DisplayName("accepted wherever a revision is")
    class Accepted {

        @Test
        void theTreeAcceptsARelativeRevision() throws Exception {
            // HEAD~1 predates third.txt, so the file must not be listed.
            mockMvc.perform(get(REPO + "/tree").param("ref", "HEAD~1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[?(@.path == 'third.txt')]").isEmpty())
                    .andExpect(jsonPath("$.entries[?(@.path == 'second.txt')]").isNotEmpty());
        }

        @Test
        void aBlobAcceptsARelativeRevision() throws Exception {
            mockMvc.perform(get(REPO + "/blob")
                            .param("ref", "HEAD~2")
                            .param("path", "README.md"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").value("# Demo\n"));
        }

        @Test
        void historyAcceptsARelativeRevision() throws Exception {
            mockMvc.perform(get(REPO + "/commits").param("ref", "HEAD~1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sha").value(parentSha));
        }

        @Test
        void theCommitPathAcceptsARelativeRevisionOnAnId() throws Exception {
            mockMvc.perform(get(REPO + "/commits/" + headSha + "~1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit.sha").value(parentSha));
        }

        @Test
        void theCommitDiffAcceptsARelativeRevisionOnAnId() throws Exception {
            mockMvc.perform(get(REPO + "/commits/" + headSha.substring(0, 8) + "~1/diff"))
                    .andExpect(status().isOk());
        }

        @Test
        void compareAcceptsRelativeRevisionsOnBothSides() throws Exception {
            mockMvc.perform(get(REPO + "/compare")
                            .param("base", "HEAD~2")
                            .param("head", "HEAD~0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.changes.changes[?(@.path == 'third.txt')]").isNotEmpty());
        }

        @Test
        void aBranchStartPointAcceptsARelativeRevision() throws Exception {
            mockMvc.perform(post(REPO + "/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"from-relative","startPoint":"HEAD~1"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get(REPO + "/commits").param("ref", "from-relative"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].sha").value(parentSha));
        }

        @Test
        void everyBaseFormTakesASuffixWhereThatFormIsAccepted() throws Exception {
            // As a ref, all four forms resolve.
            for (String base : new String[] {"HEAD", "main", headSha, headSha.substring(0, 8)}) {
                mockMvc.perform(get(REPO + "/commits").param("ref", base + "~1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].sha").value(parentSha));
            }
        }

        @Test
        void zeroResolvesToTheRevisionItself() throws Exception {
            mockMvc.perform(get(REPO + "/commits/" + headSha + "~0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit.sha").value(headSha));
        }

        @Test
        void stepsChain() throws Exception {
            mockMvc.perform(get(REPO + "/commits/" + headSha + "~1^1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit.sha").value(shaAt(2)));
        }
    }

    @Nested
    @DisplayName("each way of failing keeps its own status")
    class Failures {

        @Test
        void aMalformedExpressionIsABadRequest() throws Exception {
            mockMvc.perform(get(REPO + "/tree").param("ref", "HEAD~abc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        @Test
        void walkingPastTheRootIsNotFound() throws Exception {
            mockMvc.perform(get(REPO + "/tree").param("ref", "HEAD~99"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void aSecondParentThatDoesNotExistIsNotFound() throws Exception {
            // Every commit here has one parent, so ^2 names nothing - and must
            // never quietly answer with the first parent instead.
            mockMvc.perform(get(REPO + "/tree").param("ref", "HEAD^2"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void anUnknownBaseWithAValidSuffixIsNotFound() throws Exception {
            mockMvc.perform(get(REPO + "/tree").param("ref", "nosuchbranch~1"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("the failure is attributed to the right thing")
    class Attribution {

        @Test
        void anUnresolvableRevisionSaysSoRatherThanBlamingThePath() throws Exception {
            // The root directory exists in every commit ever made; saying it does
            // not sends the reader to look in entirely the wrong place.
            mockMvc.perform(get(REPO + "/tree").param("ref", "HEAD~99"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(containsString("No such revision")))
                    .andExpect(jsonPath("$.message").value(not(containsString("No such directory"))));
        }

        @Test
        void aMissingPathStillBlamesThePath() throws Exception {
            mockMvc.perform(get(REPO + "/tree")
                            .param("ref", "HEAD")
                            .param("path", "no/such/place"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(containsString("No such directory")));
        }

        @Test
        void anUnresolvableRevisionOnABlobSaysSoToo() throws Exception {
            mockMvc.perform(get(REPO + "/blob")
                            .param("ref", "HEAD~99")
                            .param("path", "README.md"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(containsString("No such revision")));
        }

        @Test
        void aMissingFileStillBlamesTheFile() throws Exception {
            mockMvc.perform(get(REPO + "/blob")
                            .param("ref", "HEAD")
                            .param("path", "nowhere.txt"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value(containsString("No such file")));
        }
    }

    @Nested
    @DisplayName("nothing that worked before has changed")
    class Regression {

        @Test
        void plainRevisionsStillResolve() throws Exception {
            mockMvc.perform(get(REPO + "/tree").param("ref", "HEAD")).andExpect(status().isOk());
            mockMvc.perform(get(REPO + "/tree").param("ref", "main")).andExpect(status().isOk());
            mockMvc.perform(get(REPO + "/tree").param("ref", headSha)).andExpect(status().isOk());
            mockMvc.perform(get(REPO + "/tree").param("ref", headSha.substring(0, 7)))
                    .andExpect(status().isOk());
        }

        @Test
        void anUnknownRevisionWithNoSuffixIsStillNotFoundRatherThanRefused() throws Exception {
            mockMvc.perform(get(REPO + "/tree").param("ref", "no-such-branch"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void theCommitPathStillRefusesANameWithOrWithoutASuffix() throws Exception {
            // It has only ever named an object. A suffix does not change that,
            // so HEAD~1 is refused here for the same reason HEAD always was.
            mockMvc.perform(get(REPO + "/commits/HEAD")).andExpect(status().isBadRequest());
            mockMvc.perform(get(REPO + "/commits/HEAD~1")).andExpect(status().isBadRequest());
            mockMvc.perform(get(REPO + "/commits/main~1")).andExpect(status().isBadRequest());
        }

        @Test
        void aShortIdInTheCommitPathIsStillABadRequest() throws Exception {
            mockMvc.perform(get(REPO + "/commits/" + headSha.substring(0, 3)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void readsRemainAnonymousAndWritesRemainOwnerOnly() throws Exception {
            mockMvc.perform(get(REPO + "/tree").param("ref", "HEAD~1"))
                    .andExpect(status().isOk());

            String intruder = registerAndLogin("mallory");
            mockMvc.perform(post(REPO + "/branches")
                            .header("Authorization", bearer(intruder))
                            .contentType("application/json")
                            .content("""
                                    {"name":"sneaky","startPoint":"HEAD~1"}
                                    """))
                    .andExpect(status().isForbidden());
        }
    }
}
