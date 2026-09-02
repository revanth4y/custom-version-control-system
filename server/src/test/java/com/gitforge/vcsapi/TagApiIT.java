package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tag endpoints end to end.
 *
 * <p>Reads are anonymous where the repository is public, writes are the owner's
 * alone, and neither rule is new — they are the ones {@code SecurityConfig}
 * already provides, which is why it did not change. The tests that matter most
 * here are the ones proving that: a stranger reading, a stranger refused, and a
 * signed-in non-owner refused.
 */
class TagApiIT extends AbstractIntegrationTest {

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

        mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"Initial commit","changes":[
                                  {"operation":"PUT","path":"README.md","content":"# Demo\\n"}]}
                                """))
                .andExpect(status().isCreated());
    }

    private void createTag(String body) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Nested
    @DisplayName("creating")
    class Creating {

        @Test
        void aLightweightTagIsCreatedWhenNoMessageIsGiven() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"v1.0.0","target":"main"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("v1.0.0"))
                    .andExpect(jsonPath("$.annotated").value(false))
                    .andExpect(jsonPath("$.message").doesNotExist())
                    .andExpect(jsonPath("$.commit").isNotEmpty())
                    // For a lightweight tag the ref holds the commit itself.
                    .andExpect(jsonPath("$.tip.message").value("Initial commit\n"));
        }

        @Test
        void anAnnotatedTagIsCreatedWhenAMessageIsGiven() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"v1.0.0","target":"main","message":"Release 1.0"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.annotated").value(true))
                    .andExpect(jsonPath("$.message").value("Release 1.0\n"))
                    .andExpect(jsonPath("$.taggerName").value("octocat"))
                    .andExpect(jsonPath("$.taggedAt").isNotEmpty());
        }

        @Test
        void anAnnotatedTagsRefNamesTheTagObjectAndPeelsToTheCommit() throws Exception {
            String json = mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"v1.0.0","target":"main","message":"Release 1.0"}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            var tag = objectMapper.readTree(json);

            assertThat(tag.get("target").asString()).isNotEqualTo(tag.get("commit").asString());
            assertThat(tag.get("tip").get("message").asString()).isEqualTo("Initial commit\n");
        }

        @Test
        void aTagMayBeCreatedFromACommitId() throws Exception {
            String branches = mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andReturn().getResponse().getContentAsString();
            String commit = objectMapper.readTree(branches).get(0).get("commit").asString();

            createTag("{\"name\":\"by-id\",\"target\":\"" + commit + "\"}");

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tag?name=by-id"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commit").value(commit));
        }

        @Test
        void aTagMayBeCreatedFromAnotherTag() throws Exception {
            createTag("""
                    {"name":"v1.0.0","target":"main","message":"First"}
                    """);
            createTag("""
                    {"name":"stable","target":"v1.0.0"}
                    """);

            // Resolution peels, so the second tag names the commit rather than
            // the first tag's object.
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tag?name=stable"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.annotated").value(false))
                    .andExpect(jsonPath("$.tip.message").value("Initial commit\n"));
        }

        @Test
        void aNestedTagNameIsAccepted() throws Exception {
            createTag("""
                    {"name":"release/v1.0","target":"main"}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tag?name=release/v1.0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("release/v1.0"));
        }
    }

    @Nested
    @DisplayName("refusing")
    class Refusing {

        @Test
        void aDuplicateNameIsAConflict() throws Exception {
            createTag("""
                    {"name":"v1.0.0","target":"main"}
                    """);

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"v1.0.0","target":"main"}
                                    """))
                    .andExpect(status().isConflict());
        }

        @Test
        void aTagCannotBeMovedByRecreatingIt() throws Exception {
            createTag("""
                    {"name":"v1.0.0","target":"main"}
                    """);

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Second","changes":[
                                      {"operation":"PUT","path":"a.txt","content":"a\\n"}]}
                                    """))
                    .andExpect(status().isCreated());

            // Immutability, from the outside: the only answer to "point this tag
            // somewhere else" is a conflict.
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"v1.0.0","target":"main"}
                                    """))
                    .andExpect(status().isConflict());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tag?name=v1.0.0"))
                    .andExpect(jsonPath("$.tip.message").value("Initial commit\n"));
        }

        @Test
        void anUnresolvableTargetIsNotFound() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"v1.0.0","target":"no-such-branch"}
                                    """))
                    .andExpect(status().isNotFound());
        }

        @Test
        void aTraversingNameIsABadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"../../escape","target":"main"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void aNameThatIsAnObjectIdIsABadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"a94a8fe5ccb19ba61c4c0873d391e987982fbbd3","target":"main"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void anEmptyNameIsABadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"","target":"main"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void anAbsentTagIsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tag?name=never-existed"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("listing and deleting")
    class ListingAndDeleting {

        @Test
        void tagsAreListedByName() throws Exception {
            createTag("""
                    {"name":"v2","target":"main"}
                    """);
            createTag("""
                    {"name":"v1","target":"main"}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].name").value("v1"))
                    .andExpect(jsonPath("$[1].name").value("v2"));
        }

        @Test
        void anEmptyRepositoryListsNoTags() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void aTagCanBeDeletedAndRecreated() throws Exception {
            createTag("""
                    {"name":"v1.0.0","target":"main"}
                    """);

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/tags?name=v1.0.0")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tag?name=v1.0.0"))
                    .andExpect(status().isNotFound());

            createTag("""
                    {"name":"v1.0.0","target":"main"}
                    """);
        }

        @Test
        void deletingAnAbsentTagIsNotFound() throws Exception {
            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/tags?name=never-existed")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void deletingATagLeavesTheBranchAndItsHistoryAlone() throws Exception {
            createTag("""
                    {"name":"v1.0.0","target":"main","message":"Release"}
                    """);

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/tags?name=v1.0.0")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andExpect(jsonPath("$[0].name").value("main"))
                    .andExpect(jsonPath("$[0].tip.message").value("Initial commit\n"));
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        void anAnonymousCallerMayReadAPublicRepositorysTags() throws Exception {
            createTag("""
                    {"name":"v1.0.0","target":"main"}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("v1.0.0"));
        }

        @Test
        void anAnonymousCallerMayNotCreateATag() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .contentType("application/json")
                            .content("""
                                    {"name":"v1.0.0","target":"main"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void anAnonymousCallerMayNotDeleteATag() throws Exception {
            createTag("""
                    {"name":"v1.0.0","target":"main"}
                    """);

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/tags?name=v1.0.0"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void aSignedInStrangerMayNotCreateATag() throws Exception {
            String stranger = registerAndLogin("mona");

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(stranger))
                            .contentType("application/json")
                            .content("""
                                    {"name":"v1.0.0","target":"main"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aSignedInStrangerMayNotDeleteATag() throws Exception {
            createTag("""
                    {"name":"v1.0.0","target":"main"}
                    """);
            String stranger = registerAndLogin("mona");

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/tags?name=v1.0.0")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aPrivateRepositorysTagsAreInvisibleToStrangers() throws Exception {
            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"secret","description":"hidden","visibility":"PRIVATE"}
                                    """))
                    .andExpect(status().isCreated());

            // Absent and forbidden are deliberately indistinguishable.
            mockMvc.perform(get("/api/v1/repositories/octocat/secret/tags"))
                    .andExpect(status().isNotFound());
        }
    }
}
