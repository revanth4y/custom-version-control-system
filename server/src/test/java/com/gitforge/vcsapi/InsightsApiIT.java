package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Insights API, end to end against a real database and real storage.
 *
 * <p>The reconciliation assertions matter most: merges plus non-merges equals
 * commits, the ref kinds sum to the total, type counts sum to what was scanned.
 * Those hold or the arithmetic has drifted, and they hold here against a
 * repository built by hand whose answers are known.
 */
class InsightsApiIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/repositories/octocat/demo/insights";

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = registerAndLogin("octocat");
        createRepository("demo", "PUBLIC");
    }

    private void createRepository(String name, String visibility) throws Exception {
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"description\":\"d\",\"visibility\":\""
                                + visibility + "\"}"))
                .andExpect(status().isCreated());
    }

    private void commit(String repo, String branch, String message, String path, String content)
            throws Exception {

        mockMvc.perform(post("/api/v1/repositories/octocat/" + repo + "/commits")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"branch":"%s","message":"%s","changes":[
                                  {"operation":"PUT","path":"%s","content":"%s"}]}
                                """.formatted(branch, message, path, content)))
                .andExpect(status().isCreated());
    }

    private void tag(String name, String target, String message) throws Exception {
        String body = message == null
                ? "{\"name\":\"%s\",\"target\":\"%s\"}".formatted(name, target)
                : "{\"name\":\"%s\",\"target\":\"%s\",\"message\":\"%s\"}".formatted(name, target, message);

        mockMvc.perform(post("/api/v1/repositories/octocat/demo/tags")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());
    }

    private void release(String tag, String name, boolean draft, boolean prerelease) throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/demo/releases")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("""
                                {"tag":"%s","name":"%s","draft":%s,"prerelease":%s}
                                """.formatted(tag, name, draft, prerelease)))
                .andExpect(status().isCreated());
    }

    private String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    // ------------------------------------------------------------ empty repository

    @Nested
    @DisplayName("an empty repository answers with zeroes, never an error")
    class Empty {

        @Test
        void everyEndpointAnswersSuccessfully() throws Exception {
            for (String path : new String[]{
                    "/activity", "/commits", "/commits/series", "/contributors",
                    "/branches", "/refs", "/tags", "/releases", "/issues", "/storage", "/health"}) {

                mockMvc.perform(get(BASE + path))
                        .andExpect(status().isOk());
            }
        }

        @Test
        void commitFiguresAreZeroed() throws Exception {
            mockMvc.perform(get(BASE + "/commits"))
                    .andExpect(jsonPath("$.commits").value(0))
                    .andExpect(jsonPath("$.merges").value(0))
                    .andExpect(jsonPath("$.nonMerges").value(0))
                    .andExpect(jsonPath("$.mergeRatio").value(0.0))
                    .andExpect(jsonPath("$.roots").value(0))
                    .andExpect(jsonPath("$.maxDepth").value(0))
                    // No history, so no span — null rather than an invented moment.
                    .andExpect(jsonPath("$.earliestCommit").doesNotExist());
        }

        @Test
        void refsAndStorageAreZeroed() throws Exception {
            mockMvc.perform(get(BASE + "/refs"))
                    .andExpect(jsonPath("$.branches").value(0))
                    .andExpect(jsonPath("$.tags").value(0))
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.commitsOnlyTagsProtect").value(0));

            mockMvc.perform(get(BASE + "/storage"))
                    .andExpect(jsonPath("$.storedObjects").value(0))
                    .andExpect(jsonPath("$.scannedObjects").value(0))
                    .andExpect(jsonPath("$.truncated").value(false));
        }

        @Test
        void theSeriesIsStillFullyGapFilled() throws Exception {
            String json = mockMvc.perform(get(BASE + "/commits/series?from=2026-01-01&to=2026-01-05"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andReturn().getResponse().getContentAsString();

            assertThat(objectMapper.readTree(json).get("points")).hasSize(5);
        }
    }

    // ------------------------------------------------------------ commits and DAG

    @Nested
    @DisplayName("commits and the graph")
    class Commits {

        @BeforeEach
        void history() throws Exception {
            commit("demo", "main", "First", "a.txt", "1\\n");
            commit("demo", "main", "Second", "a.txt", "2\\n");
        }

        @Test
        void mergesAndNonMergesReconcileWithCommits() throws Exception {
            String json = mockMvc.perform(get(BASE + "/commits"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits").value(2))
                    .andExpect(jsonPath("$.merges").value(0))
                    .andExpect(jsonPath("$.nonMerges").value(2))
                    .andExpect(jsonPath("$.roots").value(1))
                    .andExpect(jsonPath("$.maxDepth").value(2))
                    .andReturn().getResponse().getContentAsString();

            var node = objectMapper.readTree(json);
            assertThat(node.get("merges").asInt() + node.get("nonMerges").asInt())
                    .isEqualTo(node.get("commits").asInt());
        }

        @Test
        void theHistorySpanComesFromCommitTimestamps() throws Exception {
            mockMvc.perform(get(BASE + "/commits"))
                    .andExpect(jsonPath("$.earliestCommit").isNotEmpty())
                    .andExpect(jsonPath("$.latestCommit").isNotEmpty())
                    .andExpect(jsonPath("$.historyDurationSeconds").isNumber());
        }

        @Test
        void aTagOnlyCommitIsCountedTheWayCollectionWouldKeepIt() throws Exception {
            // Branch it, tag it, delete the branch: only the tag speaks for it.
            commit("demo", "side", "Side", "s.txt", "s\\n");
            String branches = mockMvc.perform(get("/api/v1/repositories/octocat/demo/branches"))
                    .andReturn().getResponse().getContentAsString();
            String sideTip = null;
            for (var branch : objectMapper.readTree(branches)) {
                if (branch.get("name").asString().equals("side")) {
                    sideTip = branch.get("commit").asString();
                }
            }
            tag("v-side", sideTip, null);

            mockMvc.perform(java.util.Objects.requireNonNull(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .delete("/api/v1/repositories/octocat/demo/branches?name=side"))
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            // Still counted: the root set includes tags.
            mockMvc.perform(get(BASE + "/commits"))
                    .andExpect(jsonPath("$.commits").value(3));

            mockMvc.perform(get(BASE + "/refs"))
                    .andExpect(jsonPath("$.commitsOnlyTagsProtect").value(1));
        }

        @Test
        void theSeriesTotalMatchesTheCommitCountInsideTheWindow() throws Exception {
            String json = mockMvc.perform(get(BASE + "/commits/series?from=" + today() + "&to=" + today()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(objectMapper.readTree(json).get("total").asInt()).isEqualTo(2);
        }

        @Test
        void aWeeklySeriesTotalsTheSameAsADailyOne() throws Exception {
            String daily = mockMvc.perform(get(BASE + "/commits/series?bucket=day"))
                    .andReturn().getResponse().getContentAsString();
            String weekly = mockMvc.perform(get(BASE + "/commits/series?bucket=week"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(objectMapper.readTree(weekly).get("total").asInt())
                    .isEqualTo(objectMapper.readTree(daily).get("total").asInt());
            assertThat(objectMapper.readTree(weekly).get("bucket").asString()).isEqualTo("week");
        }

        @Test
        void anUnknownBucketIsRefused() throws Exception {
            mockMvc.perform(get(BASE + "/commits/series?bucket=fortnight"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------ date ranges

    @Nested
    @DisplayName("date ranges")
    class Ranges {

        @Test
        void theDefaultWindowIsAYearOfInclusiveDays() throws Exception {
            String json = mockMvc.perform(get(BASE + "/commits/series"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            var node = objectMapper.readTree(json);
            LocalDate from = LocalDate.parse(node.get("from").asString());
            LocalDate to = LocalDate.parse(node.get("to").asString());

            assertThat(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1).isEqualTo(365);
            assertThat(to).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        }

        @Test
        void anExplicitRangeIsHonouredExactly() throws Exception {
            mockMvc.perform(get(BASE + "/commits/series?from=2026-03-01&to=2026-03-03"))
                    .andExpect(jsonPath("$.from").value("2026-03-01"))
                    .andExpect(jsonPath("$.to").value("2026-03-03"))
                    .andExpect(jsonPath("$.points.length()").value(3));
        }

        @Test
        void aSingleDayRangeIsLegalAndInclusive() throws Exception {
            mockMvc.perform(get(BASE + "/commits/series?from=2026-03-01&to=2026-03-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.points.length()").value(1));
        }

        @Test
        void threeHundredAndSixtySixDaysIsAccepted() throws Exception {
            mockMvc.perform(get(BASE + "/commits/series?from=2026-01-01&to=2026-12-32".replace("32", "31")))
                    .andExpect(status().isOk());

            mockMvc.perform(get(BASE + "/commits/series?from=2026-01-01&to="
                            + LocalDate.parse("2026-01-01").plusDays(365)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.points.length()").value(366));
        }

        @Test
        void threeHundredAndSixtySevenDaysIsRejected() throws Exception {
            mockMvc.perform(get(BASE + "/commits/series?from=2026-01-01&to="
                            + LocalDate.parse("2026-01-01").plusDays(366)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void anInvertedRangeIsRejected() throws Exception {
            mockMvc.perform(get(BASE + "/commits/series?from=2026-06-01&to=2026-01-01"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void everyDatedEndpointEnforcesTheSameCeiling() throws Exception {
            String tooWide = "?from=2026-01-01&to=" + LocalDate.parse("2026-01-01").plusDays(366);

            for (String path : new String[]{"/activity", "/contributors", "/issues", "/commits/series"}) {
                mockMvc.perform(get(BASE + path + tooWide))
                        .andExpect(status().isBadRequest());
            }
        }
    }

    // ------------------------------------------------------------ refs and tags

    @Nested
    @DisplayName("refs, tags and branches")
    class Refs {

        @BeforeEach
        void history() throws Exception {
            commit("demo", "main", "First", "a.txt", "1\\n");
            commit("demo", "main", "Second", "a.txt", "2\\n");
            commit("demo", "feature", "Feature", "f.txt", "f\\n");
        }

        @Test
        void refKindsSumToTheTotal() throws Exception {
            tag("v1", "main", null);
            tag("v2", "main", "Annotated release");

            String json = mockMvc.perform(get(BASE + "/refs"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            var node = objectMapper.readTree(json);
            assertThat(node.get("branches").asInt()
                    + node.get("tags").asInt()
                    + node.get("remoteTrackingRefs").asInt())
                    .isEqualTo(node.get("total").asInt());
            assertThat(node.get("tags").asInt()).isEqualTo(2);
        }

        @Test
        void annotatedAndLightweightSumToTheTagTotal() throws Exception {
            tag("v1", "main", null);
            tag("v2", "main", "Annotated release");

            String json = mockMvc.perform(get(BASE + "/tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.annotated").value(1))
                    .andExpect(jsonPath("$.lightweight").value(1))
                    .andReturn().getResponse().getContentAsString();

            var node = objectMapper.readTree(json);
            assertThat(node.get("annotated").asInt() + node.get("lightweight").asInt())
                    .isEqualTo(node.get("total").asInt());
        }

        @Test
        void aLightweightTagCarriesNoTaggedAtAndAnAnnotatedOneDoes() throws Exception {
            tag("light", "main", null);
            tag("heavy", "main", "Annotated");

            String json = mockMvc.perform(get(BASE + "/tags"))
                    .andReturn().getResponse().getContentAsString();

            for (var entry : objectMapper.readTree(json).get("tags")) {
                if (entry.get("name").asString().equals("light")) {
                    assertThat(entry.get("annotated").asBoolean()).isFalse();
                    assertThat(entry.get("taggedAt").isNull()).isTrue();
                } else {
                    assertThat(entry.get("annotated").asBoolean()).isTrue();
                    assertThat(entry.get("taggedAt").isNull()).isFalse();
                    // Annotated: the ref names a tag object, not the commit.
                    assertThat(entry.get("target").asString())
                            .isNotEqualTo(entry.get("commit").asString());
                }
            }
        }

        @Test
        void branchDivergenceReportsEachRelationship() throws Exception {
            String json = mockMvc.perform(get(BASE + "/branches"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andReturn().getResponse().getContentAsString();

            for (var branch : objectMapper.readTree(json).get("branches")) {
                assertThat(branch.get("ahead").isInt()).isTrue();
                assertThat(branch.get("behind").isInt()).isTrue();
                assertThat(branch.get("related").isBoolean()).isTrue();
                if (branch.get("name").asString().equals("main")) {
                    assertThat(branch.get("current").asBoolean()).isTrue();
                    assertThat(branch.get("ahead").asInt()).isZero();
                    assertThat(branch.get("behind").asInt()).isZero();
                }
            }
        }

        @Test
        void aTagWithNoReleaseIsListed() throws Exception {
            tag("v1", "main", null);

            mockMvc.perform(get(BASE + "/tags"))
                    .andExpect(jsonPath("$.withoutRelease.length()").value(1))
                    .andExpect(jsonPath("$.withoutRelease[0]").value("v1"));
        }
    }

    // ------------------------------------------------------------ releases

    @Nested
    @DisplayName("releases and draft visibility")
    class Releases {

        @BeforeEach
        void history() throws Exception {
            commit("demo", "main", "First", "a.txt", "1\\n");
            tag("v1", "main", null);
            tag("v2", "main", null);
            release("v1", "Version 1", false, false);
            release("v2", "Unfinished", true, false);
        }

        @Test
        void theOwnerSeesPublishedAndDrafts() throws Exception {
            String json = mockMvc.perform(get(BASE + "/releases")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.published").value(1))
                    .andExpect(jsonPath("$.drafts").value(1))
                    .andReturn().getResponse().getContentAsString();

            var node = objectMapper.readTree(json);
            assertThat(node.get("published").asInt() + node.get("drafts").asInt())
                    .isEqualTo(node.get("total").asInt());
        }

        @Test
        void anAnonymousReaderSeesNeitherTheDraftNorAnyTraceOfIt() throws Exception {
            mockMvc.perform(get(BASE + "/releases"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.published").value(1))
                    // Zero because none were counted, not because a flag hid one.
                    .andExpect(jsonPath("$.drafts").value(0));
        }

        @Test
        void aSignedInStrangerAlsoSeesNoDraft() throws Exception {
            String stranger = registerAndLogin("mona");

            mockMvc.perform(get(BASE + "/releases").header("Authorization", bearer(stranger)))
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.drafts").value(0));
        }

        @Test
        void aReleaseWhoseTagStillExistsIsReportedAsSuch() throws Exception {
            mockMvc.perform(get(BASE + "/releases").header("Authorization", bearer(token)))
                    .andExpect(jsonPath("$.withExistingTag").value(2))
                    .andExpect(jsonPath("$.withMissingTag").value(0));
        }
    }

    // ------------------------------------------------------------ issues

    @Nested
    @DisplayName("issues, including closures with no recorded date")
    class Issues {

        private int fileIssue(String title) throws Exception {
            String json = mockMvc.perform(post("/api/v1/repositories/octocat/demo/issues")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("{\"title\":\"" + title + "\",\"body\":\"b\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(json).get("number").asInt();
        }

        private String idOf(int number) throws Exception {
            String json = mockMvc.perform(get("/api/v1/repositories/octocat/demo/issues/" + number))
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(json).get("id").asString();
        }

        private void close(int number) throws Exception {
            mockMvc.perform(patch("/api/v1/issues/" + idOf(number))
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"status":"CLOSED"}
                                    """))
                    .andExpect(status().isOk());
        }

        @Test
        void openAndClosedCountsReconcileWithTheTotal() throws Exception {
            fileIssue("One");
            int second = fileIssue("Two");
            close(second);

            String json = mockMvc.perform(get(BASE + "/issues"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.open").value(1))
                    .andExpect(jsonPath("$.closed").value(1))
                    .andReturn().getResponse().getContentAsString();

            var node = objectMapper.readTree(json);
            assertThat(node.get("open").asInt() + node.get("closed").asInt())
                    .isEqualTo(node.get("total").asInt());
        }

        @Test
        void aClosureWithAKnownDateAppearsInTheSeries() throws Exception {
            int number = fileIssue("To close");
            close(number);

            mockMvc.perform(get(BASE + "/issues?from=" + today() + "&to=" + today()))
                    .andExpect(jsonPath("$.closedInRange").value(1))
                    .andExpect(jsonPath("$.closedUndated").value(0))
                    .andExpect(jsonPath("$.closedSeries.total").value(1));
        }

        @Test
        void aHistoricalClosureWithNoDateIsCountedButNotPlacedOnADay() throws Exception {
            int number = fileIssue("Closed long ago");
            close(number);

            // Simulate a row written before closure times were recorded.
            jdbcClearClosedAt(number);

            mockMvc.perform(get(BASE + "/issues?from=" + today() + "&to=" + today()))
                    .andExpect(jsonPath("$.closed").value(1))
                    // Counted as closed, and honestly reported as undated.
                    .andExpect(jsonPath("$.closedUndated").value(1))
                    .andExpect(jsonPath("$.closedInRange").value(0))
                    .andExpect(jsonPath("$.closedSeries.total").value(0));
        }

        @Test
        void openedInRangeCountsIssuesFiledInsideTheWindow() throws Exception {
            fileIssue("One");
            fileIssue("Two");

            mockMvc.perform(get(BASE + "/issues?from=" + today() + "&to=" + today()))
                    .andExpect(jsonPath("$.openedInRange").value(2))
                    .andExpect(jsonPath("$.openedSeries.total").value(2));
        }
    }

    /** Clears closed_at directly, standing in for a row written before the column existed. */
    private void jdbcClearClosedAt(int number) {
        jdbc().update("UPDATE issues SET closed_at = NULL WHERE number = ?", number);
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    // ------------------------------------------------------------ storage and health

    @Nested
    @DisplayName("storage and health")
    class StorageAndHealth {

        @BeforeEach
        void history() throws Exception {
            commit("demo", "main", "First", "a.txt", "1\\n");
        }

        @Test
        void typeCountsAndBytesReconcileWithTheScan() throws Exception {
            String json = mockMvc.perform(get(BASE + "/storage"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.truncated").value(false))
                    .andReturn().getResponse().getContentAsString();

            var node = objectMapper.readTree(json);
            int counts = 0;
            long bytes = 0;
            for (var type : node.get("byType")) {
                counts += type.get("count").asInt();
                bytes += type.get("bytes").asLong();
            }

            assertThat(counts).isEqualTo(node.get("scannedObjects").asInt());
            assertThat(bytes).isEqualTo(node.get("scannedBytes").asLong());
            assertThat(node.get("scannedObjects").asInt())
                    .isEqualTo(node.get("storedObjects").asInt());
        }

        @Test
        void everyObjectTypeIsRepresentedIncludingTag() throws Exception {
            tag("v1", "main", "Annotated");

            String json = mockMvc.perform(get(BASE + "/storage"))
                    .andReturn().getResponse().getContentAsString();

            boolean sawTag = false;
            for (var type : objectMapper.readTree(json).get("byType")) {
                if (type.get("type").asString().equals("tag")) {
                    sawTag = true;
                    assertThat(type.get("count").asInt()).isEqualTo(1);
                }
            }
            assertThat(sawTag).isTrue();
        }

        @Test
        void healthWithoutAScanReportsCheapCountsAndVerifiesNothing() throws Exception {
            mockMvc.perform(get(BASE + "/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scanned").value(false))
                    .andExpect(jsonPath("$.storedObjects").isNumber())
                    .andExpect(jsonPath("$.roots").isNumber())
                    // Nothing was verified, and that is what it says.
                    .andExpect(jsonPath("$.integrity").value("NOT_VERIFIED"))
                    .andExpect(jsonPath("$.reachableObjects").doesNotExist())
                    .andExpect(jsonPath("$.unreachableObjects").doesNotExist());
        }

        @Test
        void anExplicitScanReportsReachabilityAndIntegrity() throws Exception {
            String json = mockMvc.perform(get(BASE + "/health?scan=true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scanned").value(true))
                    .andExpect(jsonPath("$.integrity").value("HEALTHY"))
                    .andExpect(jsonPath("$.fullyReachable").value(true))
                    .andReturn().getResponse().getContentAsString();

            var node = objectMapper.readTree(json);
            assertThat(node.get("reachableObjects").asLong()
                    + node.get("unreachableObjects").asInt())
                    .isEqualTo(node.get("storedObjects").asLong());
        }

        @Test
        void theCheapRootCountMatchesTheSweepsRootCount() throws Exception {
            String cheap = mockMvc.perform(get(BASE + "/health"))
                    .andReturn().getResponse().getContentAsString();
            String swept = mockMvc.perform(get(BASE + "/health?scan=true"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(objectMapper.readTree(cheap).get("roots").asInt())
                    .isEqualTo(objectMapper.readTree(swept).get("roots").asInt());
        }
    }

    // ------------------------------------------------------------ authorization

    @Nested
    @DisplayName("authorization and visibility")
    class Authorization {

        @Test
        void anAnonymousCallerMayReadAPublicRepositorysInsights() throws Exception {
            commit("demo", "main", "First", "a.txt", "1\\n");

            for (String path : new String[]{
                    "/activity", "/commits", "/contributors", "/branches",
                    "/refs", "/tags", "/releases", "/issues", "/storage", "/health"}) {

                mockMvc.perform(get(BASE + path)).andExpect(status().isOk());
            }
        }

        @Test
        void aPrivateRepositoryIsInvisibleToAnonymousCallers() throws Exception {
            createRepository("secret", "PRIVATE");

            for (String path : new String[]{"/commits", "/refs", "/storage", "/health", "/issues"}) {
                mockMvc.perform(get("/api/v1/repositories/octocat/secret/insights" + path))
                        // Absent rather than forbidden, matching the rest of the API.
                        .andExpect(status().isNotFound());
            }
        }

        @Test
        void aPrivateRepositoryIsInvisibleToASignedInStranger() throws Exception {
            createRepository("secret", "PRIVATE");
            String stranger = registerAndLogin("mona");

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/insights/commits")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
        }

        @Test
        void theOwnerSeesTheirOwnPrivateRepository() throws Exception {
            createRepository("secret", "PRIVATE");

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/insights/commits")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        }

        @Test
        void anUnknownRepositoryIsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/nope/insights/commits"))
                    .andExpect(status().isNotFound());
        }
    }

    // ------------------------------------------------------------ existing contract

    @Nested
    @DisplayName("the existing endpoint is unchanged")
    class ExistingContract {

        @Test
        void theOriginalInsightsResponseKeepsItsShape() throws Exception {
            commit("demo", "main", "First", "a.txt", "1\\n");

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/insights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits").value(1))
                    .andExpect(jsonPath("$.branches").value(1))
                    .andExpect(jsonPath("$.files").isNumber())
                    .andExpect(jsonPath("$.storedObjects").isNumber())
                    .andExpect(jsonPath("$.contributors").isArray())
                    .andExpect(jsonPath("$.activity").isArray());
        }

        @Test
        void itAgreesWithTheNewCommitEndpoint() throws Exception {
            commit("demo", "main", "First", "a.txt", "1\\n");
            commit("demo", "main", "Second", "a.txt", "2\\n");

            String old = mockMvc.perform(get("/api/v1/repositories/octocat/demo/insights"))
                    .andReturn().getResponse().getContentAsString();
            String fresh = mockMvc.perform(get(BASE + "/commits"))
                    .andReturn().getResponse().getContentAsString();

            // Two endpoints, one root set: they must not disagree.
            assertThat(objectMapper.readTree(old).get("commits").asInt())
                    .isEqualTo(objectMapper.readTree(fresh).get("commits").asInt());
        }

        @Test
        void theIssueApiStillHasNoClosedAtField() throws Exception {
            String json = mockMvc.perform(post("/api/v1/repositories/octocat/demo/issues")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"title":"Contract","body":"b"}
                                    """))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            // closed_at is persisted, and deliberately not exposed here.
            assertThat(objectMapper.readTree(json).has("closedAt")).isFalse();
        }
    }
}
