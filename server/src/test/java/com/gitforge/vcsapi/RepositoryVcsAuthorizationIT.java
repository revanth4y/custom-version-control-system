package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every version-control endpoint is reached through the same authorization gate,
 * so each one must enforce the same rules. These tests check that per endpoint
 * rather than trusting that the shared path was used.
 */
class RepositoryVcsAuthorizationIT extends AbstractIntegrationTest {

    private String owner;
    private String stranger;

    @BeforeEach
    void seed() throws Exception {
        owner = registerAndLogin("owner");
        stranger = registerAndLogin("stranger");

        createRepo(owner, "public-repo", "PUBLIC");
        createRepo(owner, "secret", "PRIVATE");
        commitReadme(owner, "public-repo");
        commitReadme(owner, "secret");
    }

    private void createRepo(String token, String name, String visibility) throws Exception {
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","description":"a repo","visibility":"%s"}
                                """.formatted(name, visibility)))
                .andExpect(status().isCreated());
    }

    private void commitReadme(String token, String repo) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/owner/%s/commits".formatted(repo))
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"Initial commit","changes":[
                                  {"operation":"PUT","path":"README.md","content":"# Demo\\n"}]}
                                """))
                .andExpect(status().isCreated());
    }

    @Nested
    @DisplayName("private repositories are invisible to strangers")
    class PrivateRepositories {

        @Test
        void everyReadEndpointReportsNotFound() throws Exception {
            // 404 rather than 403 throughout: a 403 would confirm it exists.
            mockMvc.perform(get("/api/v1/repositories/owner/secret/branches")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/owner/secret/head")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/owner/secret/tree")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/owner/secret/blob")
                            .param("path", "README.md")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/owner/secret/commits")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/owner/secret/compare")
                            .param("base", "main").param("head", "main")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void anonymousCallersAlsoSeeNothing() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/owner/secret/tree"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/owner/secret/commits"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void theOwnerCanReadTheirOwnPrivateRepository() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/owner/secret/tree")
                            .header("Authorization", bearer(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.entries[0].name").value("README.md"));
        }
    }

    @Nested
    @DisplayName("only owners may mutate")
    class OwnerOnlyMutations {

        @Test
        void aStrangerCannotCommitToAPublicRepository() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/owner/public-repo/commits")
                            .header("Authorization", bearer(stranger))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Intrusion","changes":[
                                      {"operation":"PUT","path":"hack.txt","content":"x\\n"}]}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aStrangerCannotWriteContents() throws Exception {
            mockMvc.perform(put("/api/v1/repositories/owner/public-repo/contents")
                            .header("Authorization", bearer(stranger))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","path":"hack.txt","content":"x\\n","message":"Intrusion"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aStrangerCannotCreateOrDeleteBranches() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/owner/public-repo/branches")
                            .header("Authorization", bearer(stranger))
                            .contentType("application/json")
                            .content("""
                                    {"name":"intrusion","startPoint":"main"}
                                    """))
                    .andExpect(status().isForbidden());

            mockMvc.perform(delete("/api/v1/repositories/owner/public-repo/branches")
                            .param("name", "main")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aStrangerCannotMoveHead() throws Exception {
            mockMvc.perform(put("/api/v1/repositories/owner/public-repo/head")
                            .header("Authorization", bearer(stranger))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aStrangerCannotMerge() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/owner/public-repo/merge")
                            .header("Authorization", bearer(stranger))
                            .contentType("application/json")
                            .content("""
                                    {"ourBranch":"main","theirBranch":"main"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void mutatingAPrivateRepositoryReportsNotFoundRatherThanForbidden() throws Exception {
            // Existence must not leak through the mutation path either.
            mockMvc.perform(post("/api/v1/repositories/owner/secret/commits")
                            .header("Authorization", bearer(stranger))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Intrusion","changes":[
                                      {"operation":"PUT","path":"hack.txt","content":"x\\n"}]}
                                    """))
                    .andExpect(status().isNotFound());
        }

        @Test
        void theOwnerCanMutateTheirOwnRepository() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/owner/public-repo/branches")
                            .header("Authorization", bearer(owner))
                            .contentType("application/json")
                            .content("""
                                    {"name":"feature","startPoint":"main"}
                                    """))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        void unauthenticatedMutationsAreRejected() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/owner/public-repo/commits")
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Anonymous","changes":[
                                      {"operation":"PUT","path":"x.txt","content":"x\\n"}]}
                                    """))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post("/api/v1/repositories/owner/public-repo/branches")
                            .contentType("application/json")
                            .content("""
                                    {"name":"anon","startPoint":"main"}
                                    """))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(delete("/api/v1/repositories/owner/public-repo/branches").param("name", "main"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void anonymousReadsOfPublicRepositoriesAreAllowed() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/owner/public-repo/tree"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/repositories/owner/public-repo/commits"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/repositories/owner/public-repo/branches"))
                    .andExpect(status().isOk());
        }

        @Test
        void anUnknownRepositoryIsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/owner/no-such-repo/tree"))
                    .andExpect(status().isNotFound());
        }
    }
}
