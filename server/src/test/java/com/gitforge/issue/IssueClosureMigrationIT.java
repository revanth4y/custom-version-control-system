package com.gitforge.issue;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The closure timestamp, against the real schema the real migration produced.
 *
 * <p>Run here rather than only in unit tests because the questions worth asking
 * are about the database: that the column exists with the type it should, that
 * it is nullable, that the value survives a round trip through PostgreSQL, and
 * that a row written without one keeps its null.
 */
class IssueClosureMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

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

    private int fileIssue(String title) throws Exception {
        String json = mockMvc.perform(post("/api/v1/repositories/octocat/demo/issues")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"title\":\"" + title + "\",\"body\":\"details\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(json).get("number").asInt();
    }

    private String idOf(int number) throws Exception {
        String json = mockMvc.perform(get("/api/v1/repositories/octocat/demo/issues/" + number))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asString();
    }

    private void setStatus(int number, String statusValue) throws Exception {
        mockMvc.perform(patch("/api/v1/issues/" + idOf(number))
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"status\":\"" + statusValue + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(statusValue));
    }

    private Timestamp closedAtOf(int number) {
        return jdbc.queryForObject(
                "SELECT closed_at FROM issues WHERE number = ?", Timestamp.class, number);
    }

    @Nested
    @DisplayName("the migration produced the column it should have")
    class Schema {

        @Test
        void closedAtExistsIsTimestamptzAndIsNullable() {
            List<Map<String, Object>> columns = jdbc.queryForList("""
                    SELECT data_type, is_nullable
                    FROM information_schema.columns
                    WHERE table_name = 'issues' AND column_name = 'closed_at'
                    """);

            assertThat(columns).hasSize(1);
            assertThat(columns.get(0).get("data_type")).isEqualTo("timestamp with time zone");
            assertThat(columns.get(0).get("is_nullable")).isEqualTo("YES");
        }

        @Test
        void everyPreExistingIssueColumnSurvived() {
            List<String> names = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns WHERE table_name = 'issues'
                    """, String.class);

            // The V1 shape, intact, plus exactly one addition.
            assertThat(names).contains(
                    "id", "repo_id", "author_id", "number", "title", "body",
                    "status", "created_at", "updated_at", "closed_at");
        }

        @Test
        void theMigrationRanAsOneForwardStep() {
            List<Map<String, Object>> applied = jdbc.queryForList("""
                    SELECT version, description, success
                    FROM flyway_schema_history WHERE version = '4'
                    """);

            assertThat(applied).hasSize(1);
            assertThat(applied.get(0).get("success")).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("the lifecycle, through the database")
    class Lifecycle {

        @Test
        void aNewIssueIsStoredWithNoClosureTime() throws Exception {
            int number = fileIssue("Still open");

            assertThat(closedAtOf(number)).isNull();
        }

        @Test
        void closingPersistsARealTimestamp() throws Exception {
            int number = fileIssue("To be closed");
            Instant before = Instant.now().minusSeconds(1);

            setStatus(number, "CLOSED");

            Timestamp stored = closedAtOf(number);
            assertThat(stored).isNotNull();
            // A genuine clock reading that survived the round trip, not a constant.
            assertThat(stored.toInstant()).isBetween(before, Instant.now().plusSeconds(1));
        }

        @Test
        void reopeningClearsTheStoredTimestamp() throws Exception {
            int number = fileIssue("Closed then reopened");
            setStatus(number, "CLOSED");
            assertThat(closedAtOf(number)).isNotNull();

            setStatus(number, "OPEN");

            assertThat(closedAtOf(number)).isNull();
        }

        @Test
        void closingAgainAfterReopeningStoresTheNewMoment() throws Exception {
            int number = fileIssue("Cycled");
            setStatus(number, "CLOSED");
            Timestamp first = closedAtOf(number);

            setStatus(number, "OPEN");
            setStatus(number, "CLOSED");

            Timestamp second = closedAtOf(number);
            assertThat(second).isNotNull();
            assertThat(second.toInstant()).isAfterOrEqualTo(first.toInstant());
        }

        @Test
        void anUnrelatedEditDoesNotDisturbTheClosureTime() throws Exception {
            int number = fileIssue("Closed then retitled");
            setStatus(number, "CLOSED");
            Timestamp original = closedAtOf(number);

            mockMvc.perform(patch("/api/v1/issues/" + idOf(number))
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"title":"a better title"}
                                    """))
                    .andExpect(status().isOk());

            assertThat(closedAtOf(number)).isEqualTo(original);
        }

        @Test
        void closingAnAlreadyClosedIssueDoesNotResetIt() throws Exception {
            int number = fileIssue("Closed twice");
            setStatus(number, "CLOSED");
            Timestamp original = closedAtOf(number);

            setStatus(number, "CLOSED");

            assertThat(closedAtOf(number)).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("history written before the column is not invented")
    class History {

        /** A row as it would look had it been closed before this column existed. */
        private void backdateAsHistorical(int number) {
            jdbc.update("UPDATE issues SET status = 'CLOSED', closed_at = NULL WHERE number = ?", number);
        }

        @Test
        void aHistoricalClosedRowKeepsItsNull() throws Exception {
            int number = fileIssue("Closed long ago");
            backdateAsHistorical(number);

            assertThat(closedAtOf(number)).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM issues WHERE number = ?", String.class, number))
                    .isEqualTo("CLOSED");
        }

        @Test
        void editingAHistoricalClosedRowDoesNotFabricateADate() throws Exception {
            int number = fileIssue("Closed long ago");
            backdateAsHistorical(number);

            mockMvc.perform(patch("/api/v1/issues/" + idOf(number))
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"title":"tidied up years later","status":"CLOSED"}
                                    """))
                    .andExpect(status().isOk());

            // Closed but undated, and it stays that way.
            assertThat(closedAtOf(number)).isNull();
        }

        @Test
        void reopeningAHistoricalRowThenClosingItRecordsOnlyTheNewClosure() throws Exception {
            int number = fileIssue("Closed long ago");
            backdateAsHistorical(number);

            setStatus(number, "OPEN");
            assertThat(closedAtOf(number)).isNull();

            setStatus(number, "CLOSED");

            assertThat(closedAtOf(number)).isNotNull();
        }
    }

    @Nested
    @DisplayName("nothing else about an issue changed")
    class Unchanged {

        @Test
        void titleBodyAuthorAndNumberSurviveAClosure() throws Exception {
            int number = fileIssue("Untouched");

            setStatus(number, "CLOSED");

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/issues/" + number))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Untouched"))
                    .andExpect(jsonPath("$.body").value("details"))
                    .andExpect(jsonPath("$.number").value(number))
                    .andExpect(jsonPath("$.authorUsername").value("octocat"))
                    .andExpect(jsonPath("$.status").value("CLOSED"));
        }

        @Test
        void theIssueApiResponseIsUnchangedByThisPhase() throws Exception {
            int number = fileIssue("Contract check");

            String json = mockMvc.perform(get("/api/v1/repositories/octocat/demo/issues/" + number))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Phase 4 adds persistence only. Exposing closedAt is Phase 5's
            // decision, and the contract must not move before then.
            assertThat(objectMapper.readTree(json).has("closedAt")).isFalse();
        }
    }
}
