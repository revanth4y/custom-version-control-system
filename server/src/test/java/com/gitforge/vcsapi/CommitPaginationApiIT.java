package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paging through history, through the whole stack.
 *
 * <p>The claims worth testing here are the ones a single request cannot show. A
 * page that looks right proves nothing about whether the next one continues from
 * it, and the failure that matters — a commit repeated or skipped at a page
 * boundary — is invisible unless the whole walk is collected and compared
 * against the unpaged answer. So most of these tests page to exhaustion.
 */
class CommitPaginationApiIT extends AbstractIntegrationTest {

    private static final String HISTORY = "/api/v1/repositories/octocat/demo/commits";
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
    }

    /** Commits {@code count} revisions of one file, oldest first. */
    private void commitSeries(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            commit("Commit " + i, """
                    {"operation":"PUT","path":"notes.txt","content":"revision %d\\n"}
                    """.formatted(i));
        }
    }

    private void commit(String message, String change) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"main","message":"%s","changes":[%s]}
                                """.formatted(message, change)))
                .andExpect(status().isCreated());
    }

    /** One page, as parsed JSON. */
    private JsonNode page(String cursor, Integer limit, String path) throws Exception {
        var request = get(HISTORY).param("paginate", "true");
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        if (limit != null) {
            request = request.param("limit", String.valueOf(limit));
        }
        if (path != null) {
            request = request.param("path", path);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Every commit sha reachable by paging to exhaustion. */
    private List<String> pageToEnd(int pageSize, String path) throws Exception {
        List<String> shas = new ArrayList<>();
        String cursor = null;
        int guard = 0;

        do {
            JsonNode body = page(cursor, pageSize, path);
            body.get("commits").forEach(commit -> shas.add(commit.get("sha").asString()));
            cursor = body.hasNonNull("nextCursor") ? body.get("nextCursor").asString() : null;

            if (++guard > 500) {
                throw new AssertionError("Paging did not terminate — a cursor is not advancing");
            }
        } while (cursor != null);

        return shas;
    }

    @Nested
    @DisplayName("one page")
    class FirstPage {

        @Test
        void returnsTheEnvelopeWhenPaginationIsAskedFor() throws Exception {
            commitSeries(5);

            mockMvc.perform(get(HISTORY).param("paginate", "true").param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits").isArray())
                    .andExpect(jsonPath("$.commits.length()").value(2))
                    .andExpect(jsonPath("$.hasMore").value(true))
                    .andExpect(jsonPath("$.nextCursor").isNotEmpty());
        }

        @Test
        void keepsTheCommitElementShapeUnchanged() throws Exception {
            // The element is the same CommitSummaryResponse the array returns;
            // only the wrapper is new.
            commitSeries(2);

            mockMvc.perform(get(HISTORY).param("paginate", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits[0].sha").isNotEmpty())
                    .andExpect(jsonPath("$.commits[0].message").isNotEmpty())
                    .andExpect(jsonPath("$.commits[0].authorName").value("octocat"))
                    .andExpect(jsonPath("$.commits[0].committerName").value("octocat"))
                    .andExpect(jsonPath("$.commits[0].committerTimestamp").isNotEmpty());
        }

        @Test
        void aCursorAloneIsEnoughToAskForPagination() throws Exception {
            commitSeries(4);
            String cursor = page(null, 2, null).get("nextCursor").asString();

            // No paginate=true this time. A cursor could only have come from a
            // paginated response, so asking to continue is asking to paginate.
            mockMvc.perform(get(HISTORY).param("cursor", cursor).param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits").isArray())
                    .andExpect(jsonPath("$.hasMore").exists());
        }
    }

    @Nested
    @DisplayName("continuing")
    class Continuation {

        @Test
        void theSecondPageStartsWhereTheFirstEnded() throws Exception {
            commitSeries(6);

            JsonNode first = page(null, 3, null);
            JsonNode second = page(first.get("nextCursor").asString(), 3, null);

            List<String> firstShas = shasOf(first);
            List<String> secondShas = shasOf(second);

            assertThat(firstShas).hasSize(3);
            assertThat(secondShas).hasSize(3);
            assertThat(secondShas).doesNotContainAnyElementsOf(firstShas);
        }

        @Test
        void pagingMatchesTheUnpagedAnswerExactly() throws Exception {
            /* The central claim. A page is a window onto the same walk, so the
               concatenation of every page must equal what the array endpoint
               returns — same commits, same order, no gap at any boundary. */
            commitSeries(20);

            String body = mockMvc.perform(get(HISTORY).param("limit", "200"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> unpaged = new ArrayList<>();
            objectMapper.readTree(body).forEach(commit -> unpaged.add(commit.get("sha").asString()));

            assertThat(pageToEnd(3, null)).isEqualTo(unpaged);
        }

        @Test
        void noCommitIsRepeatedAcrossAWholeWalk() throws Exception {
            commitSeries(25);

            List<String> all = pageToEnd(4, null);

            assertThat(all).doesNotHaveDuplicates();
            assertThat(all).hasSize(25);
        }

        @Test
        void aSpentCursorReturnsTheSamePageAgain() throws Exception {
            // Idempotent, not an error. A client that retries a timed-out request
            // must not be punished for it.
            commitSeries(8);
            String cursor = page(null, 3, null).get("nextCursor").asString();

            assertThat(shasOf(page(cursor, 3, null)))
                    .isEqualTo(shasOf(page(cursor, 3, null)));
        }

        @Test
        void theSameCursorIsDeterministic() throws Exception {
            commitSeries(10);
            JsonNode first = page(null, 4, null);
            String cursor = first.get("nextCursor").asString();

            assertThat(page(cursor, 4, null).toString())
                    .isEqualTo(page(cursor, 4, null).toString());
        }

        private List<String> shasOf(JsonNode body) {
            List<String> shas = new ArrayList<>();
            body.get("commits").forEach(commit -> shas.add(commit.get("sha").asString()));
            return shas;
        }
    }

    @Nested
    @DisplayName("the end")
    class EndOfHistory {

        @Test
        void theFinalPageSaysSoAndCarriesNoCursor() throws Exception {
            commitSeries(4);

            mockMvc.perform(get(HISTORY).param("paginate", "true").param("limit", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits.length()").value(4))
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist());
        }

        @Test
        void aPageThatExactlyFillsTheHistoryStillEndsIt() throws Exception {
            /* The off-by-one worth having a test for: four commits and a page of
               four is the end, not a page with an empty one behind it. */
            commitSeries(4);

            mockMvc.perform(get(HISTORY).param("paginate", "true").param("limit", "4"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits.length()").value(4))
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist());
        }

        @Test
        void anEmptyRepositoryPagesToNothingRatherThanFailing() throws Exception {
            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"blank","description":"nothing yet","visibility":"PUBLIC"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/blank/commits").param("paginate", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits.length()").value(0))
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist());
        }

        @Test
        void aSingleCommitIsOnePageAndTheEnd() throws Exception {
            commitSeries(1);

            mockMvc.perform(get(HISTORY).param("paginate", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits.length()").value(1))
                    .andExpect(jsonPath("$.hasMore").value(false));
        }
    }

    @Nested
    @DisplayName("past the old ceiling")
    class BeyondTheCap {

        @Test
        void historyContinuesBeyondTwoHundredCommits() throws Exception {
            /* The limitation this version exists to remove. Two hundred was the
               end of what the API would admit to; it must now be a page size at
               most. */
            commitSeries(210);

            List<String> all = pageToEnd(50, null);

            assertThat(all).hasSize(210);
            assertThat(all).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("page size")
    class PageSize {

        @Test
        void defaultsToThirty() throws Exception {
            commitSeries(35);

            mockMvc.perform(get(HISTORY).param("paginate", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits.length()").value(30));
        }

        @Test
        void clampsAnExcessiveRequestRatherThanRefusingIt() throws Exception {
            commitSeries(5);

            mockMvc.perform(get(HISTORY).param("paginate", "true").param("limit", "100000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits.length()").value(5));
        }

        @Test
        void clampsZeroAndNegativeUpToOne() throws Exception {
            commitSeries(5);

            mockMvc.perform(get(HISTORY).param("paginate", "true").param("limit", "0"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits.length()").value(1));

            mockMvc.perform(get(HISTORY).param("paginate", "true").param("limit", "-10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits.length()").value(1));
        }
    }

    @Nested
    @DisplayName("refused cursors")
    class BadCursors {

        @Test
        void aMalformedCursorIsRefused() throws Exception {
            commitSeries(3);

            mockMvc.perform(get(HISTORY).param("cursor", "not-a-cursor!!"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void aCursorForAnotherRevisionIsRefused() throws Exception {
            /* Without this the client would page one branch under another
               branch's name and see history belonging to neither. */
            commitSeries(6);
            String cursor = page(null, 2, null).get("nextCursor").asString();

            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"side","startPoint":"main"}
                                    """))
                    .andExpect(status().isCreated());

            // Onto side, not main — otherwise side still points at the very
            // commit the cursor recorded and there is nothing to disagree about.
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"side","message":"Only on side","changes":[
                                      {"operation":"PUT","path":"side.txt","content":"side\\n"}]}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get(HISTORY).param("cursor", cursor).param("ref", "side"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }

        @Test
        void aCursorForAnotherPathFilterIsRefused() throws Exception {
            commitSeries(6);
            String cursor = page(null, 2, null).get("nextCursor").asString();

            mockMvc.perform(get(HISTORY).param("cursor", cursor).param("path", "notes.txt"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void aCursorNamingAnUnknownCommitIsRefused() throws Exception {
            commitSeries(3);
            String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("v1:" + "0".repeat(40) + ":0:").getBytes(StandardCharsets.UTF_8));

            mockMvc.perform(get(HISTORY).param("cursor", forged))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void aCursorFromAnotherRepositoryDoesNotCrossOver() throws Exception {
            // It names a commit that repository does not contain, and is refused
            // as such — no page of someone else's history.
            commitSeries(4);
            String cursor = page(null, 2, null).get("nextCursor").asString();

            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"other","description":"separate","visibility":"PUBLIC"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/repositories/octocat/other/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Elsewhere","changes":[
                                      {"operation":"PUT","path":"a.txt","content":"a\\n"}]}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/repositories/octocat/other/commits").param("cursor", cursor))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void anUnresolvableRefIsStillNotFoundRatherThanAnEmptyPage() throws Exception {
            commitSeries(3);

            mockMvc.perform(get(HISTORY).param("paginate", "true").param("ref", "no-such-branch"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("filtered by path")
    class Filtered {

        @Test
        void pagesThroughOnlyTheMatchingCommits() throws Exception {
            for (int i = 0; i < 6; i++) {
                commit("Notes " + i, """
                        {"operation":"PUT","path":"notes.txt","content":"note %d\\n"}
                        """.formatted(i));
                commit("Other " + i, """
                        {"operation":"PUT","path":"other.txt","content":"other %d\\n"}
                        """.formatted(i));
            }

            List<String> matched = pageToEnd(2, "notes.txt");

            assertThat(matched).hasSize(6);
            assertThat(matched).doesNotHaveDuplicates();
        }

        @Test
        void aFilteredWalkEndsWhenTheHistoryDoes() throws Exception {
            commitSeries(4);

            mockMvc.perform(get(HISTORY)
                            .param("paginate", "true")
                            .param("path", "notes.txt")
                            .param("limit", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasMore").value(false))
                    .andExpect(jsonPath("$.nextCursor").doesNotExist());
        }

        @Test
        void aPathThatNeverExistedEndsRatherThanPagingForever() throws Exception {
            commitSeries(5);

            assertThat(pageToEnd(2, "never/existed.txt")).isEmpty();
        }
    }

    @Nested
    @DisplayName("visibility")
    class Visibility {

        @Test
        void aPrivateRepositoryIsNotFoundOnEveryPage() throws Exception {
            String other = registerAndLogin("stranger");

            mockMvc.perform(post("/api/v1/repositories")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"secret","description":"mine","visibility":"PRIVATE"}
                                    """))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/repositories/octocat/secret/commits")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"branch":"main","message":"Private","changes":[
                                      {"operation":"PUT","path":"a.txt","content":"a\\n"}]}
                                    """))
                    .andExpect(status().isCreated());

            String cursor = mockMvc.perform(get("/api/v1/repositories/octocat/secret/commits")
                            .header("Authorization", bearer(token))
                            .param("paginate", "true").param("limit", "1"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // The owner's own cursor, in a stranger's hands, is not a key.
            JsonNode body = objectMapper.readTree(cursor);
            String next = body.hasNonNull("nextCursor") ? body.get("nextCursor").asString() : "";

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/commits")
                            .header("Authorization", bearer(other))
                            .param("cursor", next)
                            .param("paginate", "true"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/commits").param("paginate", "true"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("backward compatibility")
    class Compatibility {

        @Test
        void withoutAskingTheResponseIsStillABareArray() throws Exception {
            commitSeries(3);

            mockMvc.perform(get(HISTORY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].sha").isNotEmpty());
        }

        @Test
        void paginateFalseIsTheOldBehaviour() throws Exception {
            commitSeries(3);

            mockMvc.perform(get(HISTORY).param("paginate", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.commits").doesNotExist());
        }

        @Test
        void theOldParametersKeepWorkingUnpaginated() throws Exception {
            commitSeries(10);

            mockMvc.perform(get(HISTORY).param("limit", "4").param("ref", "main"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(4));
        }

        @Test
        void theFirstPageMatchesTheArrayItReplaces() throws Exception {
            // Same commits in the same order; only the wrapper differs.
            commitSeries(8);

            String array = mockMvc.perform(get(HISTORY).param("limit", "5"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<String> fromArray = new ArrayList<>();
            objectMapper.readTree(array).forEach(commit -> fromArray.add(commit.get("sha").asString()));

            List<String> fromPage = new ArrayList<>();
            page(null, 5, null).get("commits").forEach(c -> fromPage.add(c.get("sha").asString()));

            assertThat(fromPage).isEqualTo(fromArray);
        }
    }
}
