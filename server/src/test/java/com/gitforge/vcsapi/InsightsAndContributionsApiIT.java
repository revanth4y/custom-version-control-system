package com.gitforge.vcsapi;

import com.gitforge.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InsightsAndContributionsApiIT extends AbstractIntegrationTest {

    private String token;

    @BeforeEach
    void seed() throws Exception {
        token = registerAndLogin("octocat");
        createRepo(token, "demo", "PUBLIC");
    }

    private void createRepo(String tokenValue, String name, String visibility) throws Exception {
        mockMvc.perform(post("/api/v1/repositories")
                        .header("Authorization", bearer(tokenValue))
                        .contentType("application/json")
                        .content("""
                                {"name":"%s","description":"a repo","visibility":"%s"}
                                """.formatted(name, visibility)))
                .andExpect(status().isCreated());
    }

    private void commit(String tokenValue, String repo, String branch, String message, String changes)
            throws Exception {
        mockMvc.perform(post("/api/v1/repositories/octocat/%s/commits".formatted(repo))
                        .header("Authorization", bearer(tokenValue))
                        .contentType("application/json")
                        .content("""
                                {"branch":"%s","message":"%s","changes":[%s]}
                                """.formatted(branch, message, changes)))
                .andExpect(status().isCreated());
    }

    @Nested
    @DisplayName("insights")
    class Insights {

        @Test
        void reportsRealCountsFromTheObjectStore() throws Exception {
            commit(token, "demo", "main", "Initial commit", """
                    {"operation":"PUT","path":"README.md","content":"# Demo\\n"},
                    {"operation":"PUT","path":"src/App.java","content":"class App {}\\n"}
                    """);
            commit(token, "demo", "main", "Second commit", """
                    {"operation":"PUT","path":"b.txt","content":"b\\n"}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/insights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits").value(2))
                    .andExpect(jsonPath("$.branches").value(1))
                    .andExpect(jsonPath("$.files").value(3))
                    // A direct window onto the content-addressed store.
                    .andExpect(jsonPath("$.storedObjects").isNumber())
                    .andExpect(jsonPath("$.contributors.length()").value(1))
                    .andExpect(jsonPath("$.contributors[0].email").value("octocat@example.com"))
                    .andExpect(jsonPath("$.contributors[0].commits").value(2))
                    .andExpect(jsonPath("$.activity").isArray());
        }

        @Test
        void countsCommitsOnEveryBranch() throws Exception {
            commit(token, "demo", "main", "Initial commit", """
                    {"operation":"PUT","path":"a.txt","content":"a\\n"}
                    """);
            mockMvc.perform(post("/api/v1/repositories/octocat/demo/branches")
                            .header("Authorization", bearer(token))
                            .contentType("application/json")
                            .content("""
                                    {"name":"feature","startPoint":"main"}
                                    """))
                    .andExpect(status().isCreated());
            commit(token, "demo", "feature", "Feature work", """
                    {"operation":"PUT","path":"b.txt","content":"b\\n"}
                    """);

            mockMvc.perform(get("/api/v1/repositories/octocat/demo/insights"))
                    .andExpect(jsonPath("$.commits").value(2))
                    .andExpect(jsonPath("$.branches").value(2));
        }

        @Test
        void anEmptyRepositoryReportsZeroes() throws Exception {
            mockMvc.perform(get("/api/v1/repositories/octocat/demo/insights"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.commits").value(0))
                    .andExpect(jsonPath("$.branches").value(0))
                    .andExpect(jsonPath("$.files").value(0));
        }

        @Test
        void insightsOnAPrivateRepositoryAreHiddenFromStrangers() throws Exception {
            String stranger = registerAndLogin("stranger");
            createRepo(token, "secret", "PRIVATE");

            mockMvc.perform(get("/api/v1/repositories/octocat/secret/insights")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("contributions")
    class Contributions {

        @Test
        void countsRealCommitsOnTheDayTheyWereAuthored() throws Exception {
            commit(token, "demo", "main", "Initial commit", """
                    {"operation":"PUT","path":"a.txt","content":"a\\n"}
                    """);
            commit(token, "demo", "main", "Second commit", """
                    {"operation":"PUT","path":"b.txt","content":"b\\n"}
                    """);

            String today = LocalDate.now(ZoneOffset.UTC).toString();

            mockMvc.perform(get("/api/v1/users/octocat/contributions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    // Every day in the window is present so a calendar needs no gap filling.
                    .andExpect(jsonPath("$.days.length()").value(365))
                    .andExpect(jsonPath("$.days[364].date").value(today))
                    .andExpect(jsonPath("$.days[364].count").value(2));
        }

        @Test
        void aUserWithNoCommitsHasAnEmptyCalendarRatherThanNoData() throws Exception {
            mockMvc.perform(get("/api/v1/users/octocat/contributions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.days.length()").value(365));
        }

        @Test
        void privateRepositoryActivityIsHiddenFromStrangers() throws Exception {
            String stranger = registerAndLogin("stranger");
            createRepo(token, "secret", "PRIVATE");
            commit(token, "secret", "main", "Private work", """
                    {"operation":"PUT","path":"a.txt","content":"a\\n"}
                    """);

            // The owner sees their own work...
            mockMvc.perform(get("/api/v1/users/octocat/contributions")
                            .header("Authorization", bearer(token)))
                    .andExpect(jsonPath("$.total").value(1));

            // ...but a stranger must not learn that it happened.
            mockMvc.perform(get("/api/v1/users/octocat/contributions")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        void attributionFollowsTheAuthorNotTheRepositoryOwner() throws Exception {
            String other = registerAndLogin("contributor");
            commit(token, "demo", "main", "Initial commit", """
                    {"operation":"PUT","path":"a.txt","content":"a\\n"}
                    """);

            // A commit by the owner is not credited to someone else.
            mockMvc.perform(get("/api/v1/users/contributor/contributions")
                            .header("Authorization", bearer(other)))
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        void honoursAnExplicitRange() throws Exception {
            commit(token, "demo", "main", "Initial commit", """
                    {"operation":"PUT","path":"a.txt","content":"a\\n"}
                    """);

            LocalDate today = LocalDate.now(ZoneOffset.UTC);

            mockMvc.perform(get("/api/v1/users/octocat/contributions")
                            .param("from", today.minusDays(6).toString())
                            .param("to", today.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.days.length()").value(7))
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        void rejectsAnInvertedOrOversizedRange() throws Exception {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);

            mockMvc.perform(get("/api/v1/users/octocat/contributions")
                            .param("from", today.toString())
                            .param("to", today.minusDays(1).toString()))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(get("/api/v1/users/octocat/contributions")
                            .param("from", today.minusYears(3).toString())
                            .param("to", today.toString()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void anUnknownUserIsNotFound() throws Exception {
            mockMvc.perform(get("/api/v1/users/nobody/contributions"))
                    .andExpect(status().isNotFound());
        }
    }
}
