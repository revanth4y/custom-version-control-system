package com.gitforge.vcsapi;

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

/**
 * The release endpoints end to end.
 *
 * <p>Three rules carry most of the weight and each has its own group below: a
 * release must name a tag that exists, a draft belongs to the owner alone, and
 * neither deleting a release nor editing one may disturb the tag it names.
 */
class ReleaseApiIT extends AbstractIntegrationTest {

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

        mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"v1.0.0","target":"main","message":"Release 1.0"}
                                """))
                .andExpect(status().isCreated());
    }

    private String createRelease(String body) throws Exception {
        String json = mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(json).get("id").asString();
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        void aReleaseIsCreatedAgainstAnExistingTag() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"tag":"v1.0.0","name":"Version 1.0","body":"First release.",
                                     "draft":false,"prerelease":false}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.tag").value("v1.0.0"))
                    .andExpect(jsonPath("$.name").value("Version 1.0"))
                    .andExpect(jsonPath("$.body").value("First release."))
                    .andExpect(jsonPath("$.draft").value(false))
                    .andExpect(jsonPath("$.prerelease").value(false))
                    .andExpect(jsonPath("$.authorName").value("octocat"))
                    .andExpect(jsonPath("$.publishedAt").isNotEmpty());
        }

        @Test
        void aReleaseIsRetrievedById() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tag").value("v1.0.0"));
        }

        @Test
        void releasesAreListedNewestFirst() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"v2.0.0","target":"main"}
                                    """))
                    .andExpect(status().isCreated());

            createRelease("""
                    {"tag":"v1.0.0","name":"One","draft":false,"prerelease":false}
                    """);
            createRelease("""
                    {"tag":"v2.0.0","name":"Two","draft":false,"prerelease":false}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].name").value("Two"));
        }

        @Test
        void metadataIsEdited() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","body":"Draft notes",
                     "draft":false,"prerelease":false}
                    """);

            mockMvc.perform(patch("/api/v1/repositories/octocat/demo/releases/" + id)
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"Version 1.0 (corrected)","body":"Better notes"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Version 1.0 (corrected)"))
                    .andExpect(jsonPath("$.body").value("Better notes"))
                    // Untouched fields stay put.
                    .andExpect(jsonPath("$.tag").value("v1.0.0"))
                    .andExpect(jsonPath("$.prerelease").value(false));
        }

        @Test
        void anEditCannotChangeTheTag() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                    """);

            // There is no tag field on the update request, so a client that sends
            // one is simply ignored rather than silently obeyed.
            mockMvc.perform(patch("/api/v1/repositories/octocat/demo/releases/" + id)
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"Renamed","tag":"something-else"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tag").value("v1.0.0"));
        }

        @Test
        void aPrereleaseIsMarkedAsOne() throws Exception {
            createRelease("""
                    {"tag":"v1.0.0","name":"Release candidate","draft":false,"prerelease":true}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases"))
                    .andExpect(jsonPath("$[0].prerelease").value(true));
        }

        @Test
        void aMalformedIdIsABadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases/not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void anAbsentReleaseIsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases/"
                            + "00000000-0000-0000-0000-000000000000"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("a release must name a tag that exists")
    class TagConsistency {

        @Test
        void anUnknownTagIsNotFound() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"tag":"v9.9.9","name":"Ghost","draft":false,"prerelease":false}
                                    """))
                    .andExpect(status().isNotFound());
        }

        @Test
        void aBranchNameIsNotATag() throws Exception {
            // Resolution would happily turn "main" into a commit; a release names
            // a tag specifically, so this is refused.
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"tag":"main","name":"From a branch","draft":false,"prerelease":false}
                                    """))
                    .andExpect(status().isNotFound());
        }

        @Test
        void twoReleasesCannotShareATag() throws Exception {
            createRelease("""
                    {"tag":"v1.0.0","name":"First","draft":false,"prerelease":false}
                    """);

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"tag":"v1.0.0","name":"Second","draft":false,"prerelease":false}
                                    """))
                    .andExpect(status().isConflict());
        }

        @Test
        void aTagReferencedByAReleaseCannotBeDeleted() throws Exception {
            createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                    """);

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/tags?name=v1.0.0")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isConflict());

            // Still there, and still describing the same commit.
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tag?name=v1.0.0"))
                    .andExpect(status().isOk());
        }

        @Test
        void deletingTheReleaseFirstThenFreesTheTag() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                    """);

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/releases/" + id)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/tags?name=v1.0.0")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());
        }

        @Test
        void deletingAReleaseLeavesItsTagAlone() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                    """);

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/releases/" + id)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            // The whole point: taking down the note does not take down the history.
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/tag?name=v1.0.0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.annotated").value(true))
                    .andExpect(jsonPath("$.tip.message").value("Initial commit\n"));
        }
    }

    @Nested
    @DisplayName("drafts")
    class Drafts {

        @Test
        void aDraftHasNoPublicationTime() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"tag":"v1.0.0","name":"Unfinished","draft":true,"prerelease":false}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.draft").value(true))
                    .andExpect(jsonPath("$.publishedAt").doesNotExist());
        }

        @Test
        void theOwnerSeesTheirOwnDraft() throws Exception {
            createRelease("""
                    {"tag":"v1.0.0","name":"Unfinished","draft":true,"prerelease":false}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(token)))
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        void anAnonymousReaderDoesNotSeeADraftInTheListing() throws Exception {
            createRelease("""
                    {"tag":"v1.0.0","name":"Unfinished","draft":true,"prerelease":false}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        void aDraftIsNotFoundRatherThanForbiddenToAStranger() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Unfinished","draft":true,"prerelease":false}
                    """);
            String stranger = registerAndLogin("mona");

            // Not 403: telling a stranger a draft exists is itself a disclosure.
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases/" + id)
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void publishingADraftStampsItAndMakesItVisible() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Unfinished","draft":true,"prerelease":false}
                    """);

            mockMvc.perform(patch("/api/v1/repositories/octocat/demo/releases/" + id)
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"draft":false}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.draft").value(false))
                    .andExpect(jsonPath("$.publishedAt").isNotEmpty());

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases"))
                    .andExpect(jsonPath("$.length()").value(1));
        }

        @Test
        void returningToDraftClearsThePublicationTime() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                    """);

            mockMvc.perform(patch("/api/v1/repositories/octocat/demo/releases/" + id)
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"draft":true}
                                    """))
                    .andExpect(status().isOk())
                    // A draft claiming a publication date is a draft nobody can trust.
                    .andExpect(jsonPath("$.publishedAt").doesNotExist());
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        void anAnonymousCallerMayReadPublishedReleases() throws Exception {
            createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/releases"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("Version 1.0"));
        }

        @Test
        void anAnonymousCallerMayNotCreateARelease() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .contentType("application/json")
                            .content("""
                                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void aSignedInStrangerMayNotCreateARelease() throws Exception {
            String stranger = registerAndLogin("mona");

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(stranger))
                            .contentType("application/json")
                            .content("""
                                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aSignedInStrangerMayNotEditARelease() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                    """);
            String stranger = registerAndLogin("mona");

            mockMvc.perform(patch("/api/v1/repositories/octocat/demo/releases/" + id)
                            .header("Authorization", bearer(stranger))
                            .contentType("application/json")
                            .content("""
                                    {"name":"Hijacked"}
                                    """))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aSignedInStrangerMayNotDeleteARelease() throws Exception {
            String id = createRelease("""
                    {"tag":"v1.0.0","name":"Version 1.0","draft":false,"prerelease":false}
                    """);
            String stranger = registerAndLogin("mona");

            mockMvc.perform(delete("/api/v1/repositories/octocat/demo/releases/" + id)
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isForbidden());
        }

        @Test
        void aPrivateRepositorysReleasesAreInvisibleToStrangers() throws Exception {
            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"secret","description":"hidden","visibility":"PRIVATE"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/releases"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void aBlankTitleIsABadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"tag":"v1.0.0","name":"","draft":false,"prerelease":false}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void aMissingTagIsABadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"tag":"","name":"Version 1.0","draft":false,"prerelease":false}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void anOversizedBodyIsABadRequest() throws Exception {
            String body = "x".repeat(100_001);

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "tag", "v1.0.0",
                                    "name", "Version 1.0",
                                    "body", body,
                                    "draft", false,
                                    "prerelease", false))))
                    .andExpect(status().isBadRequest());
        }
    }
}
