package com.gitforge.issue;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IssueApiIT extends AbstractIntegrationTest {

    private void createRepo(String token, String name, String visibility) throws Exception {
        String body = """
                {"name":"%s","description":"a repo","visibility":"%s"}
                """.formatted(name, visibility);

        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());
    }

    private String createIssue(String token, String owner, String repo, String title) throws Exception {
        String body = """
                {"title":"%s","body":"details"}
                """.formatted(title);

        return mockMvc.perform(post("/api/v1/repositories/%s/%s/issues".formatted(owner, repo))
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void issueNumbersIncrementPerRepository() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "alpha", "PUBLIC");
        createRepo(token, "beta", "PUBLIC");

        mockMvc.perform(post("/api/v1/repositories/octocat/alpha/issues")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"title":"first","body":null}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(1));

        mockMvc.perform(post("/api/v1/repositories/octocat/alpha/issues")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"title":"second","body":null}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(2));

        // Numbering restarts in a different repository.
        mockMvc.perform(post("/api/v1/repositories/octocat/beta/issues")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"title":"first in beta","body":null}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(1));
    }

    @Test
    void anyAuthenticatedUserCanFileIssueOnPublicRepository() throws Exception {
        String owner = registerAndLogin("owner");
        String reporter = registerAndLogin("reporter");
        createRepo(owner, "alpha", "PUBLIC");

        createIssue(reporter, "owner", "alpha", "Found a bug");

        mockMvc.perform(get("/api/v1/repositories/owner/alpha/issues/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Found a bug"))
                .andExpect(jsonPath("$.authorUsername").value("reporter"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void filingIssueRequiresAuthentication() throws Exception {
        String owner = registerAndLogin("owner");
        createRepo(owner, "alpha", "PUBLIC");

        mockMvc.perform(post("/api/v1/repositories/owner/alpha/issues")
                        .contentType("application/json")
                        .content("""
                                {"title":"anonymous","body":null}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void issuesOnPrivateRepositoryAreHiddenFromStrangers() throws Exception {
        String owner = registerAndLogin("owner");
        String stranger = registerAndLogin("stranger");
        createRepo(owner, "secret", "PRIVATE");
        createIssue(owner, "owner", "secret", "internal issue");

        mockMvc.perform(get("/api/v1/repositories/owner/secret/issues")
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/repositories/owner/secret/issues"))
                .andExpect(status().isNotFound());
    }

    @Test
    void authorCanEditOwnIssue() throws Exception {
        String owner = registerAndLogin("owner");
        String reporter = registerAndLogin("reporter");
        createRepo(owner, "alpha", "PUBLIC");
        String issueId = objectMapper.readTree(createIssue(reporter, "owner", "alpha", "typo")).get("id").asString();

        mockMvc.perform(patch("/api/v1/issues/" + issueId)
                        .header("Authorization", bearer(reporter))
                        .contentType("application/json")
                        .content("""
                                {"title":"corrected title","status":"CLOSED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("corrected title"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.body").value("details"));
    }

    @Test
    void repositoryOwnerCanEditIssueFiledByAnother() throws Exception {
        String owner = registerAndLogin("owner");
        String reporter = registerAndLogin("reporter");
        createRepo(owner, "alpha", "PUBLIC");
        String issueId = objectMapper.readTree(createIssue(reporter, "owner", "alpha", "bug")).get("id").asString();

        mockMvc.perform(patch("/api/v1/issues/" + issueId)
                        .header("Authorization", bearer(owner))
                        .contentType("application/json")
                        .content("""
                                {"status":"CLOSED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void unrelatedUserCannotEditIssue() throws Exception {
        String owner = registerAndLogin("owner");
        String reporter = registerAndLogin("reporter");
        String outsider = registerAndLogin("outsider");
        createRepo(owner, "alpha", "PUBLIC");
        String issueId = objectMapper.readTree(createIssue(reporter, "owner", "alpha", "bug")).get("id").asString();

        mockMvc.perform(patch("/api/v1/issues/" + issueId)
                        .header("Authorization", bearer(outsider))
                        .contentType("application/json")
                        .content("""
                                {"title":"vandalised"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void unrelatedUserCannotDeleteIssue() throws Exception {
        String owner = registerAndLogin("owner");
        String outsider = registerAndLogin("outsider");
        createRepo(owner, "alpha", "PUBLIC");
        String issueId = objectMapper.readTree(createIssue(owner, "owner", "alpha", "bug")).get("id").asString();

        mockMvc.perform(delete("/api/v1/issues/" + issueId).header("Authorization", bearer(outsider)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/repositories/owner/alpha/issues/1")).andExpect(status().isOk());
    }

    @Test
    void issuesCanBeFilteredByStatus() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "alpha", "PUBLIC");
        String openIssue = createIssue(token, "octocat", "alpha", "still open");
        String toClose = createIssue(token, "octocat", "alpha", "will close");

        String closeId = objectMapper.readTree(toClose).get("id").asString();
        mockMvc.perform(patch("/api/v1/issues/" + closeId)
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"status":"CLOSED"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/repositories/octocat/alpha/issues").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("still open"));

        mockMvc.perform(get("/api/v1/repositories/octocat/alpha/issues").param("status", "CLOSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("will close"));

        mockMvc.perform(get("/api/v1/repositories/octocat/alpha/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Sanity: the untouched issue is still open.
        org.assertj.core.api.Assertions
                .assertThat(objectMapper.readTree(openIssue).get("status").asString())
                .isEqualTo("OPEN");
    }

    @Test
    void missingIssueReturnsNotFound() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "alpha", "PUBLIC");

        mockMvc.perform(get("/api/v1/repositories/octocat/alpha/issues/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankTitleIsRejected() throws Exception {
        String token = registerAndLogin("octocat");
        createRepo(token, "alpha", "PUBLIC");

        mockMvc.perform(post("/api/v1/repositories/octocat/alpha/issues")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"title":"   ","body":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
