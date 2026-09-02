package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Garbage collection over HTTP.
 *
 * <p>Two things are checked that the engine tests cannot: that reporting and
 * collecting carry different authorization — anyone who may read the repository
 * may ask what is collectible, only the owner may collect — and that nothing in
 * the application triggers a collection on its own.
 */
class GcApiIT extends AbstractIntegrationTest {

    private static final String REPO = "/api/v1/repositories/octocat/demo";
    private static final String GC = REPO + "/gc";

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

        commit("Initial commit", "README.md", "# Demo\\n");
        commit("Second commit", "second.txt", "second\\n");
    }

    private void commit(String message, String path, String content) throws Exception {
        commitOn("main", message, path, content);
    }

    private void commitOn(String branch, String message, String path, String content)
            throws Exception {
        mockMvc.perform(post(REPO + "/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"%s","message":"%s","changes":[
                                  {"operation":"PUT","path":"%s","content":"%s"}
                                ]}
                                """.formatted(branch, message, path, content)))
                .andExpect(status().isCreated());
    }

    /** A branch created and deleted, leaving its commit behind: real garbage. */
    private void strandAcommit() throws Exception {
        mockMvc.perform(post(REPO + "/branches")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"doomed","startPoint":"HEAD"}
                                """))
                .andExpect(status().isCreated());

        // Commit onto the doomed branch, not main, so that deleting the branch is
        // what strands the commit. A commit on main would stay reachable.
        commitOn("doomed", "On the doomed branch only", "doomed.txt", "gone soon\\n");

        mockMvc.perform(delete(REPO + "/branches")
                        .header("Authorization", bearer(token))
                        .param("name", "doomed"))
                .andExpect(status().isNoContent());
    }

    @Nested
    @DisplayName("a clean repository reports nothing to collect")
    class Clean {

        @Test
        void reportingFindsNoGarbage() throws Exception {
            mockMvc.perform(get(GC))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unreachableObjects").value(0))
                    .andExpect(jsonPath("$.collectedObjects").value(0))
                    .andExpect(jsonPath("$.collectionPerformed").value(false))
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andExpect(jsonPath("$.storedObjects").value(greaterThan(0)))
                    .andExpect(jsonPath("$.reachableObjects").value(greaterThan(0)));
        }

        @Test
        void everyStoredObjectIsReachable() throws Exception {
            String body = mockMvc.perform(get(GC))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            var json = objectMapper.readTree(body);
            org.assertj.core.api.Assertions
                    .assertThat(json.get("reachableObjects").asLong())
                    .isEqualTo(json.get("storedObjects").asLong());
        }

        @Test
        void collectingIsASafeNoOp() throws Exception {
            mockMvc.perform(post(GC).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.collectedObjects").value(0))
                    .andExpect(jsonPath("$.reclaimedBytes").value(0))
                    .andExpect(jsonPath("$.collectionPerformed").value(true));

            // The repository still reads correctly afterwards.
            mockMvc.perform(get(REPO + "/tree").param("ref", "HEAD"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("garbage is found, and removed only when asked")
    class Collecting {

        @Test
        void aDeletedBranchLeavesCollectibleObjectsBehind() throws Exception {
            strandAcommit();

            mockMvc.perform(get(GC))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unreachableObjects").value(greaterThan(0)))
                    .andExpect(jsonPath("$.unreachableBytes").value(greaterThan(0)))
                    .andExpect(jsonPath("$.unreachable[0].type").exists())
                    .andExpect(jsonPath("$.collectedObjects").value(0));
        }

        @Test
        void collectingRemovesThemAndTheRepositoryStillReads() throws Exception {
            strandAcommit();

            mockMvc.perform(post(GC).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.collectedObjects").value(greaterThan(0)))
                    .andExpect(jsonPath("$.reclaimedBytes").value(greaterThan(0)))
                    .andExpect(jsonPath("$.collectionPerformed").value(true));

            mockMvc.perform(get(REPO + "/tree").param("ref", "HEAD"))
                    .andExpect(status().isOk());
            mockMvc.perform(get(REPO + "/commits"))
                    .andExpect(status().isOk());

            // Nothing left, and a second collection is a no-op.
            mockMvc.perform(post(GC).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.collectedObjects").value(0));
        }

        @Test
        void integrityStillPassesAfterACollection() throws Exception {
            strandAcommit();
            mockMvc.perform(post(GC).header("Authorization", bearer(token)))
                    .andExpect(status().isOk());

            mockMvc.perform(get(REPO + "/integrity"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.healthy").value(true))
                    .andExpect(jsonPath("$.damaged").isEmpty());
        }
    }

    @Nested
    @DisplayName("collection is owner-only; reporting follows read visibility")
    class Authorization {

        @Test
        void anonymousMayReportOnAPublicRepository() throws Exception {
            mockMvc.perform(get(GC)).andExpect(status().isOk());
        }

        @Test
        void anonymousMayNotCollect() throws Exception {
            mockMvc.perform(post(GC)).andExpect(status().isUnauthorized());
        }

        @Test
        void aSignedInStrangerMayNotCollect() throws Exception {
            String intruder = registerAndLogin("mallory");

            mockMvc.perform(post(GC).header("Authorization", bearer(intruder)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aPrivateRepositoryHidesItsReportFromStrangers() throws Exception {
            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"secret","description":"hidden","visibility":"PRIVATE"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/gc"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/gc")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("nothing collects on its own")
    class NeverImplicit {

        @Test
        void deletingABranchDoesNotReclaimItsObjects() throws Exception {
            strandAcommit();

            // The branch is gone. If deletion had collected, there would be
            // nothing left for an explicit sweep to find.
            mockMvc.perform(get(GC))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unreachableObjects").value(greaterThan(0)));
        }

        @Test
        void committingDoesNotReclaimExistingGarbage() throws Exception {
            strandAcommit();
            commit("An unrelated commit", "unrelated.txt", "still here\\n");

            mockMvc.perform(get(GC))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unreachableObjects").value(greaterThan(0)));
        }
    }
}
