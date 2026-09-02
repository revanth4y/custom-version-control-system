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
 * The remote endpoints over HTTP.
 *
 * <p>What this covers is the layer the engine tests cannot: who may call what.
 * Advertisement and object reads are anonymous on a public repository and
 * invisible on a private one; everything that writes is owner-only. The transfer
 * logic itself is covered by {@code RemoteTransferTest}, and the socket by
 * {@code RemoteCrossInstanceIT}.
 */
class RemoteApiIT extends AbstractIntegrationTest {

    private static final String REPO = "/api/v1/repositories/octocat/demo";

    private String token;
    private String headSha;

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

        mockMvc.perform(post(REPO + "/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"Initial commit","changes":[
                                  {"operation":"PUT","path":"README.md","content":"# Demo\\n"}
                                ]}
                                """))
                .andExpect(status().isCreated());

        String history = mockMvc.perform(get(REPO + "/commits"))
                .andReturn().getResponse().getContentAsString();
        headSha = objectMapper.readTree(history).get(0).get("sha").asString();
    }

    @Nested
    @DisplayName("what a peer may read")
    class Advertisement {

        @Test
        void branchesAndTipsAreAdvertisedAnonymously() throws Exception {
            mockMvc.perform(get(REPO + "/remote-refs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refs[0].branch").value("main"))
                    .andExpect(jsonPath("$.refs[0].commit").value(headSha));
        }

        @Test
        void objectsAreServedByIdAnonymously() throws Exception {
            mockMvc.perform(get(REPO + "/remote-objects").param("id", headSha))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.objects[0].id").value(headSha))
                    .andExpect(jsonPath("$.objects[0].type").value("commit"))
                    .andExpect(jsonPath("$.objects[0].payload").isNotEmpty());
        }

        @Test
        void anIdThatNamesNothingIsSimplyAbsent() throws Exception {
            mockMvc.perform(get(REPO + "/remote-objects").param("id", "0".repeat(40)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.objects").isEmpty());
        }

        @Test
        void negotiationReportsOnlyWhatIsAbsent() throws Exception {
            mockMvc.perform(get(REPO + "/remote-objects/missing")
                            .param("id", headSha)
                            .param("id", "0".repeat(40)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.missing.length()").value(1))
                    .andExpect(jsonPath("$.missing[0]").value("0".repeat(40)));
        }

        @Test
        void aMalformedIdIsRefused() throws Exception {
            mockMvc.perform(get(REPO + "/remote-objects").param("id", "not-hex"))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void tooManyIdsInOneRequestIsRefused() throws Exception {
            var request = get(REPO + "/remote-objects");
            for (int index = 0; index <= 32; index++) {
                request = request.param("id", headSha);
            }
            mockMvc.perform(request).andExpect(status().isUnprocessableEntity());
        }

        @Test
        void aPrivateRepositoryAdvertisesNothingToStrangers() throws Exception {
            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"secret","description":"hidden","visibility":"PRIVATE"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/remote-refs"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/remote-refs")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("registering remotes")
    class Registration {

        @Test
        void anOwnerMayRegisterAndListAndForget() throws Exception {
            mockMvc.perform(post(REPO + "/remotes")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"origin","url":"https://93.184.216.34/api/v1/repositories/a/b"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("origin"));

            mockMvc.perform(get(REPO + "/remotes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("origin"));

            mockMvc.perform(delete(REPO + "/remotes")
                            .header("Authorization", bearer(token))
                            .param("name", "origin"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get(REPO + "/remotes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        void aUrlPointingAtAPrivateAddressIsRefused() throws Exception {
            mockMvc.perform(post(REPO + "/remotes")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"evil","url":"http://169.254.169.254/latest/meta-data"}
                                    """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void aNonHttpSchemeIsRefused() throws Exception {
            mockMvc.perform(post(REPO + "/remotes")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"evil","url":"file:///etc/passwd"}
                                    """))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void forgettingSomethingAbsentIsNotFound() throws Exception {
            mockMvc.perform(delete(REPO + "/remotes")
                            .header("Authorization", bearer(token))
                            .param("name", "nope"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void fetchingAnUnregisteredRemoteIsNotFound() throws Exception {
            mockMvc.perform(post(REPO + "/remotes/nope/fetch")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("writes are owner-only")
    class Authorization {

        @Test
        void anonymousMayNotRegisterFetchPushOrReceive() throws Exception {
            mockMvc.perform(post(REPO + "/remotes").contentType("application/json")
                            .content("""
                                    {"name":"origin","url":"https://93.184.216.34/a"}
                                    """))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post(REPO + "/remotes/origin/fetch"))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post(REPO + "/remote-objects/receive")
                            .contentType("application/json")
                            .content("""
                                    {"objects":[]}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void aSignedInStrangerMayNotRegisterOrReceive() throws Exception {
            String intruder = registerAndLogin("mallory");

            mockMvc.perform(post(REPO + "/remotes")
                            .header("Authorization", bearer(intruder))
                            .contentType("application/json")
                            .content("""
                                    {"name":"origin","url":"https://93.184.216.34/a"}
                                    """))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post(REPO + "/remote-objects/receive")
                            .header("Authorization", bearer(intruder))
                            .contentType("application/json")
                            .content("""
                                    {"objects":[]}
                                    """))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("receiving")
    class Receiving {

        @Test
        void anEmptyReceiveStoresNothingAndMovesNothing() throws Exception {
            mockMvc.perform(post(REPO + "/remote-objects/receive")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"objects":[]}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.storedObjects").value(0))
                    .andExpect(jsonPath("$.branch").doesNotExist());
        }

        @Test
        void anObjectThatDoesNotHashToItsIdIsRefused() throws Exception {
            mockMvc.perform(post(REPO + "/remote-objects/receive")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"objects":[{"id":"%s","type":"blob","payload":"dGFtcGVyZWQ="}]}
                                    """.formatted("0".repeat(40))))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        void askingToMoveABranchOntoHistoryThatWasNeverSentIsRefused() throws Exception {
            mockMvc.perform(post(REPO + "/remote-objects/receive")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"objects":[],"branch":"newbranch","commit":"%s"}
                                    """.formatted("1".repeat(40))))
                    .andExpect(status().isUnprocessableEntity());

            // And nothing appeared.
            mockMvc.perform(get(REPO + "/branches"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.name == 'newbranch')]").isEmpty());
        }
    }

    @Nested
    @DisplayName("nothing here disturbs what was already there")
    class Regression {

        @Test
        void trackingRefsDoNotAppearAmongBranches() throws Exception {
            mockMvc.perform(get(REPO + "/branches"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("main"));
        }

        @Test
        void collectionStillReportsACleanRepository() throws Exception {
            mockMvc.perform(get(REPO + "/gc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.unreachableObjects").value(0))
                    .andExpect(jsonPath("$.storedObjects").value(greaterThan(0)));
        }

        @Test
        void integrityIsStillHealthy() throws Exception {
            mockMvc.perform(get(REPO + "/integrity"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.healthy").value(true));
        }
    }
}
