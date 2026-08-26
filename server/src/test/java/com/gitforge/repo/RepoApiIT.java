package com.gitforge.repo;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Repository endpoints exercised through the full security filter chain.
 *
 * <p>The cross-user cases here are the ones the previous Node implementation
 * failed: it had no authorization checks at all.
 */
class RepoApiIT extends AbstractIntegrationTest {

    private String createRepo(String token, String name, String visibility) throws Exception {
        String body = """
                {"name":"%s","description":"a repo","visibility":"%s"}
                """.formatted(name, visibility);

        return mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String idOf(String repoJson) {
        return objectMapper.readTree(repoJson).get("id").asString();
    }

    @Test
    void ownerCanCreateAndFetchRepository() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "portfolio", "PUBLIC");

        mockMvc.perform(get("/api/v1/repositories/octocat/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("portfolio"))
                .andExpect(jsonPath("$.ownerUsername").value("octocat"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    void creatingRepositoryRequiresAuthentication() throws Exception {
        String body = """
                {"name":"portfolio","description":"a repo","visibility":"PUBLIC"}
                """;

        mockMvc.perform(post("/api/v1/repositories").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateNameForSameOwnerIsRejected() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "portfolio", "PUBLIC");

        String body = """
                {"name":"portfolio","description":"again","visibility":"PUBLIC"}
                """;

        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void twoUsersMayEachOwnARepositoryWithTheSameName() throws Exception {
        // The previous schema made repository names globally unique, which meant
        // only one account on the whole platform could own a given name.
        String alice = registerAndLogin("alice");
        String bob = registerAndLogin("bob");

        createRepo(alice, "portfolio", "PUBLIC");
        createRepo(bob, "portfolio", "PUBLIC");

        mockMvc.perform(get("/api/v1/repositories/alice/portfolio")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repositories/bob/portfolio")).andExpect(status().isOk());
    }

    @Test
    void invalidRepositoryNameIsRejected() throws Exception {
        String token = registerAndLogin("octocat");
        String body = """
                {"name":"has spaces/and-slashes","description":null,"visibility":"PUBLIC"}
                """;

        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void strangerCannotUpdateAnotherUsersRepository() throws Exception {
        String owner = registerAndLogin("owner");
        String stranger = registerAndLogin("stranger");
        String repoId = idOf(createRepo(owner, "portfolio", "PUBLIC"));

        mockMvc.perform(patch("/api/v1/repositories/" + repoId)
                        .header("Authorization", bearer(stranger))
                        .contentType("application/json")
                        .content("""
                                {"description":"hijacked"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void strangerCannotDeleteAnotherUsersRepository() throws Exception {
        String owner = registerAndLogin("owner");
        String stranger = registerAndLogin("stranger");
        String repoId = idOf(createRepo(owner, "portfolio", "PUBLIC"));

        mockMvc.perform(delete("/api/v1/repositories/" + repoId)
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());

        // Still present afterwards.
        mockMvc.perform(get("/api/v1/repositories/owner/portfolio")).andExpect(status().isOk());
    }

    @Test
    void ownerCanUpdateAndDeleteOwnRepository() throws Exception {
        String token = registerAndLogin("octocat");
        String repoId = idOf(createRepo(token, "portfolio", "PUBLIC"));

        mockMvc.perform(patch("/api/v1/repositories/" + repoId)
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"description":"updated description"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("updated description"))
                .andExpect(jsonPath("$.name").value("portfolio"));

        mockMvc.perform(delete("/api/v1/repositories/" + repoId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/repositories/octocat/portfolio"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingARepositoryRemovesItsStorageAndNoOneElses() throws Exception {
        String token = registerAndLogin("octocat");
        String doomedId = idOf(createRepo(token, "doomed", "PUBLIC"));
        String keptId = idOf(createRepo(token, "kept", "PUBLIC"));

        // Something worth losing: an empty repository would pass a weaker test
        // by having little on disk to begin with.
        mockMvc.perform(post("/api/v1/repositories/octocat/doomed/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"Add a file","changes":[
                                  {"operation":"PUT","path":"README.md","content":"# doomed\\n"}]}
                                """))
                .andExpect(status().isCreated());

        Path doomed = STORAGE_ROOT.resolve(doomedId);
        Path kept = STORAGE_ROOT.resolve(keptId);
        assertThat(doomed).isDirectory();
        assertThat(kept).isDirectory();

        mockMvc.perform(delete("/api/v1/repositories/" + doomedId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        /* The record going was never the question; the objects staying behind
           was. Nothing points at them once the row is gone, so they could not be
           found again to be removed later. */
        assertThat(doomed).doesNotExist();

        // The storage id is the repository's own UUID, so deleting one cannot
        // reach another's directory.
        assertThat(kept).isDirectory();
        mockMvc.perform(get("/api/v1/repositories/octocat/kept")).andExpect(status().isOk());
    }

    @Test
    void aRefusedDeleteLeavesStorageAlone() throws Exception {
        String owner = registerAndLogin("octocat");
        String repoId = idOf(createRepo(owner, "portfolio", "PUBLIC"));
        String stranger = registerAndLogin("hubot");

        mockMvc.perform(delete("/api/v1/repositories/" + repoId)
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());

        // Authorization is checked before anything is removed, so a refusal
        // costs the repository nothing.
        assertThat(STORAGE_ROOT.resolve(repoId)).isDirectory();
        mockMvc.perform(get("/api/v1/repositories/octocat/portfolio")).andExpect(status().isOk());
    }

    @Test
    void privateRepositoryIsHiddenFromAnonymousCallers() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "secret", "PRIVATE");

        mockMvc.perform(get("/api/v1/repositories/octocat/secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    void privateRepositoryIsHiddenFromOtherUsers() throws Exception {
        String owner = registerAndLogin("owner");
        String stranger = registerAndLogin("stranger");
        createRepo(owner, "secret", "PRIVATE");

        // 404 rather than 403: a 403 would confirm the repository exists.
        mockMvc.perform(get("/api/v1/repositories/owner/secret")
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanSeeOwnPrivateRepository() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "secret", "PRIVATE");

        mockMvc.perform(get("/api/v1/repositories/octocat/secret")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
    }

    @Test
    void ownerListingIncludesPrivateReposOnlyForTheOwner() throws Exception {
        String owner = registerAndLogin("owner");
        createRepo(owner, "public-repo", "PUBLIC");
        createRepo(owner, "secret", "PRIVATE");

        mockMvc.perform(get("/api/v1/users/owner/repositories")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/users/owner/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("public-repo"));
    }

    @Test
    void publicDiscoveryListingExcludesPrivateRepositories() throws Exception {
        String owner = registerAndLogin("owner");
        createRepo(owner, "public-repo", "PUBLIC");
        createRepo(owner, "secret", "PRIVATE");

        mockMvc.perform(get("/api/v1/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("public-repo"));
    }
}
