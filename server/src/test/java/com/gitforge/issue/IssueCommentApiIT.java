package com.gitforge.issue;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IssueCommentApiIT extends AbstractIntegrationTest {

    private String owner;
    private String commenter;

    @BeforeEach
    void seed() throws Exception {
        owner = registerAndLogin("owner");
        commenter = registerAndLogin("commenter");

        createRepo(owner, "demo", "PUBLIC");
        createIssue(owner, "demo", "Something is broken");
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

    private void createIssue(String token, String repo, String title) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/owner/%s/issues".formatted(repo))
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"title":"%s","body":"details"}
                                """.formatted(title)))
                .andExpect(status().isCreated());
    }

    private String comment(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/repositories/owner/demo/issues/1/comments")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"body":"%s"}
                                """.formatted(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private String idOf(String commentJson) {
        return objectMapper.readTree(commentJson).get("id").asString();
    }

    @Nested
    @DisplayName("writing and reading")
    class Writing {

        @Test
        void anyAuthenticatedReaderCanComment() throws Exception {
            comment(commenter, "I can reproduce this");

            mockMvc.perform(get("/api/v1/repositories/owner/demo/issues/1/comments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].body").value("I can reproduce this"))
                    .andExpect(jsonPath("$[0].authorUsername").value("commenter"))
                    .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
        }

        @Test
        void commentsAreReturnedInTheOrderTheyWereWritten() throws Exception {
            comment(owner, "First");
            comment(commenter, "Second");
            comment(owner, "Third");

            mockMvc.perform(get("/api/v1/repositories/owner/demo/issues/1/comments"))
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].body").value("First"))
                    .andExpect(jsonPath("$[2].body").value("Third"));
        }

        @Test
        void anIssueWithNoCommentsReturnsAnEmptyList() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/owner/demo/issues/1/comments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void commentingRequiresAuthentication() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/owner/demo/issues/1/comments")
                            .contentType("application/json")
                            .content("""
                                    {"body":"anonymous"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void anEmptyCommentIsRejected() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/owner/demo/issues/1/comments")
                            .header("Authorization", bearer(commenter))
                            .contentType("application/json")
                            .content("""
                                    {"body":"   "}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        void nonAsciiContentRoundTripsExactly() throws Exception {
            // Sent and stored as UTF-8; a lossy conversion anywhere in the path
            // would corrupt these characters rather than fail loudly.
            String body = "An em dash — and 変更 and émoji";

            mockMvc.perform(post("/api/v1/repositories/owner/demo/issues/1/comments")
                            .header("Authorization", bearer(commenter))
                            .contentType("application/json;charset=UTF-8")
                            .content(("{\"body\":\"" + body + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/owner/demo/issues/1/comments"))
                    .andExpect(jsonPath("$[0].body").value("An em dash — and 変更 and émoji"));
        }

        @Test
        void aMalformedRequestBodyIsARejectedRequestNotAServerFault() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/owner/demo/issues/1/comments")
                            .header("Authorization", bearer(commenter))
                            .contentType("application/json")
                            .content("{\"body\": not valid json}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }

        @Test
        void commentingOnAnAbsentIssueIsNotFound() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/owner/demo/issues/99/comments")
                            .header("Authorization", bearer(commenter))
                            .contentType("application/json")
                            .content("""
                                    {"body":"ghost"}
                                    """))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("editing and deleting")
    class Editing {

        @Test
        void theAuthorCanEditTheirOwnComment() throws Exception {
            String id = idOf(comment(commenter, "Original"));

            mockMvc.perform(patch("/api/v1/issue-comments/" + id)
                            .header("Authorization", bearer(commenter))
                            .contentType("application/json")
                            .content("""
                                    {"body":"Corrected"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.body").value("Corrected"));
        }

        @Test
        void theRepositoryOwnerCanModerateAnyComment() throws Exception {
            String id = idOf(comment(commenter, "Off topic"));

            mockMvc.perform(delete("/api/v1/issue-comments/" + id)
                            .header("Authorization", bearer(owner)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/repositories/owner/demo/issues/1/comments"))
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void anUnrelatedUserCannotEditOrDelete() throws Exception {
            String outsider = registerAndLogin("outsider");
            String id = idOf(comment(commenter, "Mine"));

            mockMvc.perform(patch("/api/v1/issue-comments/" + id)
                            .header("Authorization", bearer(outsider))
                            .contentType("application/json")
                            .content("""
                                    {"body":"vandalised"}
                                    """))
                    .andExpect(status().isForbidden());

            mockMvc.perform(delete("/api/v1/issue-comments/" + id)
                            .header("Authorization", bearer(outsider)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void anAbsentCommentIsNotFound() throws Exception {
            mockMvc.perform(delete("/api/v1/issue-comments/" + java.util.UUID.randomUUID())
                            .header("Authorization", bearer(owner)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("private repositories")
    class Privacy {

        @Test
        void commentsOnAPrivateRepositoryAreHiddenFromStrangers() throws Exception {
            createRepo(owner, "secret", "PRIVATE");
            createIssue(owner, "secret", "Internal");

            mockMvc.perform(post("/api/v1/repositories/owner/secret/issues/1/comments")
                            .header("Authorization", bearer(owner))
                            .contentType("application/json")
                            .content("""
                                    {"body":"internal note"}
                                    """))
                    .andExpect(status().isCreated());

            // 404 rather than 403: a 403 would confirm the repository exists.
            mockMvc.perform(get("/api/v1/repositories/owner/secret/issues/1/comments")
                            .header("Authorization", bearer(commenter)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/repositories/owner/secret/issues/1/comments"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void theOwnerCanReadCommentsOnTheirPrivateRepository() throws Exception {
            createRepo(owner, "secret", "PRIVATE");
            createIssue(owner, "secret", "Internal");

            mockMvc.perform(get("/api/v1/repositories/owner/secret/issues/1/comments")
                            .header("Authorization", bearer(owner)))
                    .andExpect(status().isOk());
        }
    }
}
