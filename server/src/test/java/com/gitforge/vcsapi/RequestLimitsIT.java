package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import com.gitforge.common.web.RequestSizeLimitFilter;
import com.gitforge.user.User;
import com.gitforge.user.UserService;
import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.object.Signature;
import com.gitforge.vcs.repository.FileChange;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How much a single request is allowed to ask the server to do.
 *
 * <p>Before these limits existed a twelve-megabyte file and a two-thousand-file
 * commit were both accepted without comment, and nothing bounded the request
 * body at all. Each test here also checks the other half of the contract: that
 * a refusal leaves the repository exactly as it was.
 */
class RequestLimitsIT extends AbstractIntegrationTest {

    private String token;

    @Autowired
    private VcsRepositoryProvider repositories;

    @Autowired
    private UserService users;

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

        commit("""
                {"operation":"PUT","path":"README.md","content":"hello"}
                """)
                .andExpect(status().isCreated());
    }

    private ResultActions commit(String changes) throws Exception {
        return mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                .header("Authorization", bearer(token))
                .contentType("application/json")
                .content("""
                        {"branch":"main","message":"a commit","changes":[%s]}
                        """.formatted(changes)));
    }

    /** A PUT of {@code size} bytes of plain text, which the API reads as UTF-8. */
    private static String textChange(String path, int size) {
        return """
                {"operation":"PUT","path":"%s","content":"%s"}
                """.formatted(path, "a".repeat(size));
    }

    private String headSha() throws Exception {
        String history = mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("limit", "1"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(history).get(0).get("sha").asString();
    }

    /** Total bytes on disk, so a rejected commit can be shown to have written none. */
    private long storedBytes() throws Exception {
        try (var paths = Files.walk(STORAGE_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .sum();
        }
    }

    /** A single-file write, the path that used to reach the engine unmeasured. */
    private ResultActions putContents(String path, int size) throws Exception {
        return mockMvc.perform(put("/api/v1/repositories/octocat/demo/contents")
                .header("Authorization", bearer(token))
                .contentType("application/json")
                .content("""
                        {"branch":"main","path":"%s","message":"write a file","content":"%s"}
                        """.formatted(path, "a".repeat(size))));
    }

    @Test
    void aSingleFileWriteAtTheLimitIsAccepted() throws Exception {
        putContents("at-limit.txt", ContentLimits.MAX_BLOB_BYTES)
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "at-limit.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(ContentLimits.MAX_BLOB_BYTES));
    }

    @Test
    void aSingleFileWriteOverTheLimitIsRefusedAndNothingIsWritten() throws Exception {
        /* This path once went straight to the engine: the measuring happens on
           the way in from a commit request, and writing one file does not come
           that way. A file too large to read back could be stored, and the only
           endpoint that serves contents would then refuse it. */
        String before = headSha();
        long bytesBefore = storedBytes();

        putContents("too-big.txt", ContentLimits.MAX_BLOB_BYTES + 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("too-big.txt")));

        assertThat(headSha()).isEqualTo(before);
        assertThat(storedBytes()).isEqualTo(bytesBefore);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "too-big.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFileTooLargeToServeIsRefusedRatherThanSent() throws Exception {
        /* Written underneath the API, because no endpoint will accept it any
           more. That is the only way such a file can exist - seeded, restored,
           or stored before the write paths agreed on a limit - and it is exactly
           the case the read check is here for. */
        writeBelowTheApi("oversized.bin", new byte[ContentLimits.MAX_BLOB_BYTES + 1]);

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/blob").param("path", "oversized.bin"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("oversized.bin")));

        // The listing still shows it: the file exists, it just cannot be served
        // through this endpoint.
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.name == 'oversized.bin')]").exists());
    }

    /** Commits through the engine, below every check the API applies. */
    private void writeBelowTheApi(String path, byte[] content) {
        User owner = users.requireByUsername("octocat");

        repositories.forWrite("octocat", "demo", owner).commits().commit(
                "main",
                List.of(FileChange.put(path, content, FileMode.REGULAR_FILE)),
                Signature.of("octocat", "octocat@gitforge.test", Instant.now()),
                "written below the API");
    }

    @Test
    void historyWithoutAPathIsUnchanged() throws Exception {
        /* Path filtering answers history now rather than refusing it, so the
           refusal this class used to assert has moved on; what it protected is
           now covered by the path-history tests. What still belongs here is that
           the unfiltered listing is untouched by any of it. */
        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sha").value(headSha()));

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/repositories/octocat/demo/commits").param("ref", "main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sha").value(headSha()));
    }

    @Test
    void aFileWithinTheBlobLimitIsCommitted() throws Exception {
        String before = headSha();

        commit(textChange("large.txt", 2 * 1024 * 1024))
                .andExpect(status().isCreated());

        assertThat(headSha()).isNotEqualTo(before);
    }

    @Test
    void aFileOverTheBlobLimitIsRefusedAndNothingIsWritten() throws Exception {
        String before = headSha();
        long bytesBefore = storedBytes();

        commit(textChange("huge.txt", CommitApiService.MAX_BLOB_BYTES + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("huge.txt")));

        // The limit is applied to decoded content before the engine is asked to
        // store anything, so the refusal costs no disk and moves no branch.
        assertThat(headSha()).isEqualTo(before);
        assertThat(storedBytes()).isEqualTo(bytesBefore);
    }

    @Test
    void filesThatAreIndividuallyFineButTooLargeTogetherAreRefused() throws Exception {
        String before = headSha();
        long bytesBefore = storedBytes();

        int each = 7 * 1024 * 1024;
        assertThat(each).isLessThan(CommitApiService.MAX_BLOB_BYTES);
        assertThat(2L * each).isGreaterThan(CommitApiService.MAX_COMMIT_BYTES);

        commit(textChange("one.txt", each) + "," + textChange("two.txt", each))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(headSha()).isEqualTo(before);
        assertThat(storedBytes()).isEqualTo(bytesBefore);
    }

    @Test
    void tooManyChangesInOneCommitAreRefused() throws Exception {
        String before = headSha();

        String changes = IntStream.rangeClosed(0, CommitApiService.MAX_CHANGES)
                .mapToObj(i -> """
                        {"operation":"PUT","path":"file-%d.txt","content":"x"}
                        """.formatted(i))
                .collect(Collectors.joining(","));

        commit(changes)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString(String.valueOf(CommitApiService.MAX_CHANGES))));

        assertThat(headSha()).isEqualTo(before);
    }

    @Test
    void aCommitAtTheChangeLimitStillSucceeds() throws Exception {
        String changes = IntStream.range(0, CommitApiService.MAX_CHANGES)
                .mapToObj(i -> """
                        {"operation":"PUT","path":"file-%d.txt","content":"x"}
                        """.formatted(i))
                .collect(Collectors.joining(","));

        commit(changes).andExpect(status().isCreated());
    }

    /**
     * Transport-level refusal, before any of the body is parsed, so it is 413
     * rather than the 400 an application-level limit produces.
     */
    @Test
    void aBodyOverTheTransportLimitIsRefusedOutright() throws Exception {
        String before = headSha();
        long bytesBefore = storedBytes();

        commit(textChange("enormous.txt", (int) RequestSizeLimitFilter.MAX_REQUEST_BYTES + 1))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        assertThat(headSha()).isEqualTo(before);
        assertThat(storedBytes()).isEqualTo(bytesBefore);
    }

}
