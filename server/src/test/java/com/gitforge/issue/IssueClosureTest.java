package com.gitforge.issue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When an issue records that it was closed.
 *
 * <p>The subtle case is closing something already closed. That must leave the
 * original moment alone — an edit is not a re-closure — and, for a row that
 * predates this column, it must leave the null alone rather than quietly filling
 * it with the time somebody saved an unrelated change. A fabricated date is
 * worse than a missing one, because nothing downstream can tell it apart from a
 * real one.
 */
class IssueClosureTest {

    private Issue issue() {
        return new Issue(null, null, 1, "Something", "body");
    }

    /** An issue as it would be loaded from a row written before closed_at existed. */
    private Issue historicalClosedIssue() {
        Issue issue = issue();
        setStatusDirectly(issue, IssueStatus.CLOSED);
        return issue;
    }

    /** Sets the field without going through the setter, as JPA hydration does. */
    private void setStatusDirectly(Issue issue, IssueStatus status) {
        try {
            var field = Issue.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(issue, status);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Nested
    @DisplayName("the ordinary lifecycle")
    class Lifecycle {

        @Test
        void aFreshIssueIsOpenAndHasNoClosureTime() {
            Issue issue = issue();

            assertThat(issue.getStatus()).isEqualTo(IssueStatus.OPEN);
            assertThat(issue.getClosedAt()).isNull();
        }

        @Test
        void closingRecordsWhenItHappened() {
            Issue issue = issue();
            Instant before = Instant.now();

            issue.setStatus(IssueStatus.CLOSED);

            assertThat(issue.getStatus()).isEqualTo(IssueStatus.CLOSED);
            assertThat(issue.getClosedAt()).isNotNull();
            // A real clock reading, not a constant: it falls in the window the
            // call actually occupied.
            assertThat(issue.getClosedAt()).isBetween(before, Instant.now());
        }

        @Test
        void reopeningClearsTheClosureTime() {
            Issue issue = issue();
            issue.setStatus(IssueStatus.CLOSED);
            assertThat(issue.getClosedAt()).isNotNull();

            issue.setStatus(IssueStatus.OPEN);

            assertThat(issue.getStatus()).isEqualTo(IssueStatus.OPEN);
            assertThat(issue.getClosedAt()).isNull();
        }

        @Test
        void closingAgainAfterReopeningRecordsTheNewClosure() throws Exception {
            Issue issue = issue();
            issue.setStatus(IssueStatus.CLOSED);
            Instant first = issue.getClosedAt();

            issue.setStatus(IssueStatus.OPEN);
            Thread.sleep(5);
            issue.setStatus(IssueStatus.CLOSED);

            assertThat(issue.getClosedAt()).isNotNull();
            assertThat(issue.getClosedAt()).isAfterOrEqualTo(first);
        }

        @Test
        void repeatedCyclesLeaveTheStateConsistent() {
            Issue issue = issue();

            for (int cycle = 0; cycle < 5; cycle++) {
                issue.setStatus(IssueStatus.CLOSED);
                assertThat(issue.getClosedAt()).isNotNull();

                issue.setStatus(IssueStatus.OPEN);
                assertThat(issue.getClosedAt()).isNull();
            }

            assertThat(issue.getStatus()).isEqualTo(IssueStatus.OPEN);
        }
    }

    @Nested
    @DisplayName("only a real transition touches the timestamp")
    class Transitions {

        @Test
        void closingAnAlreadyClosedIssueKeepsTheOriginalMoment() throws Exception {
            Issue issue = issue();
            issue.setStatus(IssueStatus.CLOSED);
            Instant original = issue.getClosedAt();

            Thread.sleep(5);
            issue.setStatus(IssueStatus.CLOSED);

            // An edit that leaves the status alone is not a re-closure.
            assertThat(issue.getClosedAt()).isEqualTo(original);
        }

        @Test
        void openingAnAlreadyOpenIssueChangesNothing() {
            Issue issue = issue();

            issue.setStatus(IssueStatus.OPEN);

            assertThat(issue.getStatus()).isEqualTo(IssueStatus.OPEN);
            assertThat(issue.getClosedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("history is not fabricated")
    class History {

        @Test
        void aClosedIssueFromBeforeTheColumnExistedStaysUndated() {
            Issue historical = historicalClosedIssue();

            assertThat(historical.getStatus()).isEqualTo(IssueStatus.CLOSED);
            assertThat(historical.getClosedAt()).isNull();
        }

        @Test
        void editingSuchAnIssueDoesNotInventAClosureDate() throws Exception {
            Issue historical = historicalClosedIssue();

            Thread.sleep(5);
            historical.setTitle("Retitled long after it was closed");
            historical.setStatus(IssueStatus.CLOSED);

            // Still closed, still undated. Filling this in would put a date into
            // the analytics that never happened.
            assertThat(historical.getClosedAt()).isNull();
        }

        @Test
        void reopeningSuchAnIssueThenClosingItRecordsTheNewClosureOnly() {
            Issue historical = historicalClosedIssue();

            historical.setStatus(IssueStatus.OPEN);
            assertThat(historical.getClosedAt()).isNull();

            historical.setStatus(IssueStatus.CLOSED);

            // Now there is a real closure to record, so it is recorded.
            assertThat(historical.getClosedAt()).isNotNull();
        }
    }
}
