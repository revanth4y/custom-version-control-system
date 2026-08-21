package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import com.gitforge.common.web.RequestSizeLimitFilter;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
