package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Intra-line segments over HTTP.
 *
 * <p>Separate from {@code DiffApiIT}, which is left untouched: that file pins
 * the response as it was before this feature, and it passing unchanged is the
 * evidence that nothing about the existing contract moved.
 */
class InlineDiffApiIT extends AbstractIntegrationTest {

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = registerAndLogin("octocat");
        createRepo("demo", "PUBLIC");
    }

    private void createRepo(String name, String visibility) throws Exception {
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","description":"a repo","visibility":"%s"}
                                """.formatted(name, visibility)))
                .andExpect(status().isCreated());
    }

    private void commit(String repo, String message, String changes) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/%s/commits".formatted(repo))
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"%s","changes":[%s]}
                                """.formatted(message, changes)))
                .andExpect(status().isCreated());
    }

    /** Authenticated, so it works for a private repository too. */
    private String shaAt(String repo, int index) throws Exception {
        String history = mockMvc.perform(
                        get("/api/v1/repositories/octocat/%s/commits".formatted(repo))
                                .header("Authorization", bearer(token))
                                .param("limit", "5"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(history).get(index).get("sha").asString();
    }

    private String latestSha(String repo) throws Exception {
        return shaAt(repo, 0);
    }

    private String commitDiff(String repo) throws Exception {
        return mockMvc.perform(get("/api/v1/repositories/octocat/%s/commits/%s/diff"
                        .formatted(repo, latestSha(repo))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** A one-character edit, which is the case the feature exists for. */
    private void seedOneCharacterEdit() throws Exception {
        commit("demo", "Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"timeout = 30\\n"}
                """);
        commit("demo", "Edit", """
                {"operation":"PUT","path":"a.txt","content":"timeout = 60\\n"}
                """);
    }

    @Test
    void aModifiedLineCarriesSegmentsOnBothSides() throws Exception {
        seedOneCharacterEdit();

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/%s/diff".formatted(latestSha("demo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].hunks[0].lines[0].type").value("REMOVED"))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[0].segments.length()").value(1))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[0].segments[0].start").value(10))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[0].segments[0].end").value(11))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[1].type").value("ADDED"))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[1].segments[0].start").value(10))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[1].segments[0].end").value(11));
    }

    @Test
    void segmentOffsetsIndexTheContentTheSameResponseCarries() throws Exception {
        seedOneCharacterEdit();
        var root = objectMapper.readTree(commitDiff("demo"));

        var line = root.get("files").get(0).get("hunks").get(0).get("lines").get(0);
        String content = line.get("content").asString();
        var segment = line.get("segments").get(0);

        // The one assertion a client would otherwise have to trust: the offsets
        // are valid indices into the string sent alongside them.
        assertThat(content.substring(segment.get("start").asInt(), segment.get("end").asInt()))
                .isEqualTo("3");
    }

    @Test
    void theSameLineThroughTheTwoRevisionEndpointAgrees() throws Exception {
        seedOneCharacterEdit();

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/diff")
                        .param("base", shaAt("demo", 1))
                        .param("head", shaAt("demo", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].hunks[0].lines[0].segments.length()").value(1))
                .andExpect(jsonPath("$.files[0].hunks[0].lines[1].segments.length()").value(1));
    }

    @Test
    void contextLinesCarryNoSegments() throws Exception {
        commit("demo", "Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"one\\ntwo\\nthree\\n"}
                """);
        commit("demo", "Edit", """
                {"operation":"PUT","path":"a.txt","content":"one\\nTWO\\nthree\\n"}
                """);

        var lines = objectMapper.readTree(commitDiff("demo"))
                .get("files").get(0).get("hunks").get(0).get("lines");

        for (var line : lines) {
            if ("CONTEXT".equals(line.get("type").asString())) {
                assertThat(line.has("segments")).isFalse();
            }
        }
    }

    @Test
    void anAddedFileHasNoSegmentsBecauseNothingIsPaired() throws Exception {
        commit("demo", "Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"only line\\n"}
                """);

        var lines = objectMapper.readTree(commitDiff("demo"))
                .get("files").get(0).get("hunks").get(0).get("lines");

        for (var line : lines) {
            assertThat(line.has("segments")).isFalse();
        }
    }

    @Test
    void aBinaryFileStillReportsBinaryAndCarriesNoSegments() throws Exception {
        byte[] png = new byte[64];
        png[0] = (byte) 0x89;
        png[1] = 'P';
        png[2] = 'N';
        png[3] = 'G';
        String encoded = Base64.getEncoder().encodeToString(png);

        commit("demo", "Add an image", """
                {"operation":"PUT","path":"logo.png","encoding":"base64","content":"%s"}
                """.formatted(encoded));

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/%s/diff".formatted(latestSha("demo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].binary").value(true))
                .andExpect(jsonPath("$.files[0].hunks.length()").value(0));
    }

    @Test
    void aModeOnlyChangeIsUnaffected() throws Exception {
        commit("demo", "Initial commit", """
                {"operation":"PUT","path":"run.sh","content":"echo hi\\n"}
                """);
        commit("demo", "Make it executable", """
                {"operation":"PUT","path":"run.sh","content":"echo hi\\n","mode":"100755"}
                """);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/%s/diff".formatted(latestSha("demo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].oldMode").value("100644"))
                .andExpect(jsonPath("$.files[0].newMode").value("100755"))
                .andExpect(jsonPath("$.files[0].hunks.length()").value(0));
    }

    @Test
    void theExistingResponseFieldsAreUnchanged() throws Exception {
        seedOneCharacterEdit();
        var file = objectMapper.readTree(commitDiff("demo")).get("files").get(0);

        // A client written against v2.0.8 finds everything where it was; the new
        // field is additive and nothing else moved.
        for (String field : new String[] {
                "path", "status", "oldBlob", "newBlob", "oldMode", "newMode",
                "binary", "tooLarge", "additions", "deletions", "oldSize", "newSize", "hunks"}) {
            assertThat(file.has(field)).as(field).isTrue();
        }
        assertThat(file.get("additions").asInt()).isEqualTo(1);
        assertThat(file.get("deletions").asInt()).isEqualTo(1);
    }

    @Test
    void segmentsAreOmittedRatherThanSentEmpty() throws Exception {
        commit("demo", "Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"only line\\n"}
                """);

        // An added file has no pairs, so no line should carry the key at all -
        // a client checking `"segments" in line` must not see an empty array.
        assertThat(commitDiff("demo")).doesNotContain("\"segments\":[]");
    }

    @Test
    void aPrivateRepositoryIsStillHiddenFromAnonymousCallers() throws Exception {
        createRepo("secret", "PRIVATE");
        commit("secret", "Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"one\\n"}
                """);
        String sha = latestSha("secret");

        mockMvc.perform(get("/api/v1/repositories/octocat/secret/commits/%s/diff".formatted(sha)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/repositories/octocat/secret/diff")
                        .param("base", sha).param("head", sha))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPrivateRepositoryIsStillHiddenFromOtherSignedInUsers() throws Exception {
        createRepo("secret", "PRIVATE");
        commit("secret", "Initial commit", """
                {"operation":"PUT","path":"a.txt","content":"one\\n"}
                """);
        String stranger = registerAndLogin("intruder");

        mockMvc.perform(get("/api/v1/repositories/octocat/secret/commits/%s/diff".formatted(latestSha("secret")))
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPublicDiffIsStillReadableAnonymously() throws Exception {
        seedOneCharacterEdit();

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits/%s/diff".formatted(latestSha("demo"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].hunks[0].lines[0].segments").exists());
    }
}
