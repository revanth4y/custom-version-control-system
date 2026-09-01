package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Line-level merging, over the API a client actually calls.
 *
 * <p>Two things are being checked that the engine tests cannot: that a merge
 * both sides contributed to is recorded as a real commit whose file holds both
 * edits, and that the conflict regions travel — including the case where they
 * must be absent from the JSON entirely rather than present and empty.
 */
class AdvancedMergeApiIT extends AbstractIntegrationTest {

    /** One untouched line between the edits, which is what keeps them apart. */
    private static final String BASE = "one\\ntwo\\nthree\\nfour\\nfive\\n";
    private static final String OURS = "OURS\\ntwo\\nthree\\nfour\\nfive\\n";
    private static final String THEIRS = "one\\ntwo\\nthree\\nfour\\nTHEIRS\\n";

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

        commit("main", "Initial commit", """
                {"operation":"PUT","path":"shared.txt","content":"%s"}
                """.formatted(BASE));
        branch("feature", "main");
    }

    private void commit(String branch, String message, String changes) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"%s","message":"%s","changes":[%s]}
                                """.formatted(branch, message, changes)))
                .andExpect(status().isCreated());
    }

    private void branch(String name, String startPoint) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","startPoint":"%s"}
                                """.formatted(name, startPoint)))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions merge(String ours, String theirs) throws Exception {
        return mockMvc.perform(post("/api/v1/repositories/octocat/demo/merge")
                .header("Authorization", bearer(token))
                .contentType("application/json")
                .content("""
                        {"ourBranch":"%s","theirBranch":"%s"}
                        """.formatted(ours, theirs)));
    }

    private void divergeOnSharedFile() throws Exception {
        commit("feature", "Change the last line", """
                {"operation":"PUT","path":"shared.txt","content":"%s"}
                """.formatted(THEIRS));
        commit("main", "Change the first line", """
                {"operation":"PUT","path":"shared.txt","content":"%s"}
                """.formatted(OURS));
    }

    private void collideOnSharedFile() throws Exception {
        commit("feature", "Rewrite the middle", """
                {"operation":"PUT","path":"shared.txt","content":"one\\ntwo\\nTHEIRS\\nfour\\nfive\\n"}
                """);
        commit("main", "Rewrite the middle differently", """
                {"operation":"PUT","path":"shared.txt","content":"one\\ntwo\\nOURS\\nfour\\nfive\\n"}
                """);
    }

    @Test
    void editsAtOppositeEndsOfOneFileMergeIntoARealCommit() throws Exception {
        divergeOnSharedFile();

        merge("main", "feature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MERGED"))
                .andExpect(jsonPath("$.mergeCommit").isNotEmpty())
                .andExpect(jsonPath("$.conflicts").isEmpty());
    }

    @Test
    void theMergedFileHoldsBothSidesEdits() throws Exception {
        divergeOnSharedFile();
        merge("main", "feature").andExpect(status().isOk());

        // Read back through the blob endpoint: the merge is only real if the
        // file on the branch afterwards contains both changes.
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob")
                        .param("ref", "main")
                        .param("path", "shared.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("OURS\ntwo\nthree\nfour\nTHEIRS\n"));
    }

    @Test
    void theBranchMergedIntoIsTheOnlyOneThatMoves() throws Exception {
        divergeOnSharedFile();
        String featureBefore = tipOf("feature");

        merge("main", "feature").andExpect(status().isOk());

        assertThat(tipOf("feature")).isEqualTo(featureBefore);
    }

    @Test
    void collidingEditsStillConflictAndCarryTheirRegions() throws Exception {
        collideOnSharedFile();

        merge("main", "feature")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.outcome").value("CONFLICTED"))
                .andExpect(jsonPath("$.conflicts[0].kind").value("CONTENT"))
                .andExpect(jsonPath("$.conflicts[0].path").value("shared.txt"))
                .andExpect(jsonPath("$.conflicts[0].regions[0].base.start").value(3))
                .andExpect(jsonPath("$.conflicts[0].regions[0].base.end").value(4))
                .andExpect(jsonPath("$.conflicts[0].regions[0].ours.start").value(3))
                .andExpect(jsonPath("$.conflicts[0].regions[0].theirs.start").value(3));
    }

    @Test
    void aConflictedMergeStillMovesNothing() throws Exception {
        collideOnSharedFile();
        String mainBefore = tipOf("main");

        merge("main", "feature").andExpect(status().isConflict());

        assertThat(tipOf("main")).isEqualTo(mainBefore);
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob")
                        .param("ref", "main")
                        .param("path", "shared.txt"))
                .andExpect(jsonPath("$.content").value("one\ntwo\nOURS\nfour\nfive\n"));
    }

    @Test
    void aConflictWithNothingKnownAboutItOmitsRegionsEntirely() throws Exception {
        // A modify/delete has no line-level question to answer, so the field
        // must be absent rather than sent as an empty array - a client that
        // ignores it has to see exactly what it saw before.
        commit("feature", "Delete it", """
                {"operation":"DELETE","path":"shared.txt"}
                """);
        commit("main", "Edit it", """
                {"operation":"PUT","path":"shared.txt","content":"%s"}
                """.formatted(OURS));

        String body = merge("main", "feature")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0].kind").value("MODIFY_DELETE"))
                .andExpect(jsonPath("$.conflicts[0].regions").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"regions\"");
    }

    @Test
    void everyExistingResponseFieldIsStillThere() throws Exception {
        collideOnSharedFile();

        merge("main", "feature")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.outcome").exists())
                .andExpect(jsonPath("$.conflicts[0].base.id").isNotEmpty())
                .andExpect(jsonPath("$.conflicts[0].base.mode").value("100644"))
                .andExpect(jsonPath("$.conflicts[0].base.directory").value(false))
                .andExpect(jsonPath("$.conflicts[0].ours.id").isNotEmpty())
                .andExpect(jsonPath("$.conflicts[0].theirs.id").isNotEmpty());
    }

    @Test
    void aFileOnlyOneSideChangedIsUnaffected() throws Exception {
        commit("feature", "Add a file of their own", """
                {"operation":"PUT","path":"theirs.txt","content":"theirs\\n"}
                """);
        commit("main", "Add a file of our own", """
                {"operation":"PUT","path":"ours.txt","content":"ours\\n"}
                """);

        merge("main", "feature")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MERGED"));
    }

    @Test
    void mergingRemainsOwnerOnly() throws Exception {
        divergeOnSharedFile();
        String intruder = registerAndLogin("mallory");

        mockMvc.perform(post("/api/v1/repositories/octocat/demo/merge")
                        .header("Authorization", bearer(intruder))
                        .contentType("application/json")
                        .content("""
                                {"ourBranch":"main","theirBranch":"feature"}
                                """))
                // The repository is public, so its existence is not a secret;
                // only the write is refused. A private one answers 404 instead.
                .andExpect(status().isForbidden());
    }

    @Test
    void mergingStillRequiresAuthentication() throws Exception {
        divergeOnSharedFile();

        mockMvc.perform(post("/api/v1/repositories/octocat/demo/merge")
                        .contentType("application/json")
                        .content("""
                                {"ourBranch":"main","theirBranch":"feature"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String tipOf(String branch) throws Exception {
        String body = mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                .andReturn().getResponse().getContentAsString();

        for (var node : objectMapper.readTree(body)) {
            if (node.get("name").asString().equals(branch)) {
                return node.get("commit").asString();
            }
        }
        throw new AssertionError("No such branch: " + branch);
    }
}
