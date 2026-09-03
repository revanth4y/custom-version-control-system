package com.gitforge.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance criteria, run against the real binary path.
 *
 * <p>Each of these corresponds to a numbered criterion, and each asserts the
 * three things a criterion has to pin down: what was printed, what the process
 * exited with, and what the repository looked like afterwards. A test that only
 * checked the message would pass while the tool did the wrong thing quietly.
 */
class AcceptanceCriteriaTest {

    @TempDir
    Path sandbox;

    @TempDir
    Path home;

    private CliHarness cli() {
        return new CliHarness(sandbox, home);
    }

    private void repositoryWithOneCommit() throws IOException {
        CliHarness cli = cli();
        assertThat(cli.run("init", ".").succeeded()).isTrue();
        Files.writeString(sandbox.resolve("a.txt"), "hello\n");
        assertThat(cli.run("add", "a.txt").succeeded()).isTrue();
        assertThat(cli.run("commit", "-m", "First").succeeded()).isTrue();
    }

    // ------------------------------------------------------------------ AC-1

    @Nested
    @DisplayName("AC-1: a path outside the sandbox is refused before anything is opened")
    class SandboxContainment {

        @Test
        @DisplayName("add ../../../etc/passwd exits 6 and changes nothing")
        void addOutsideTheSandbox() throws IOException {
            repositoryWithOneCommit();

            CliHarness.Result result = cli().run("--json", "add", "../../../etc/passwd");

            assertThat(result.exitCode()).isEqualTo(ExitCode.SANDBOX_VIOLATION.number());
            assertThat(result.err()).contains("SANDBOX_VIOLATION");
            assertThat(result.err()).contains("\"ok\": false");

            // Nothing was staged: the refusal happened before the path was used.
            CliHarness.Result status = cli().run("--json", "status");
            assertThat(status.out()).contains("\"staged\": []");
        }

        @Test
        @DisplayName("an absolute path elsewhere is refused the same way")
        void addAbsolutePathOutside(@TempDir Path elsewhere) throws IOException {
            repositoryWithOneCommit();
            Path secret = Files.writeString(elsewhere.resolve("secret.txt"), "not yours");

            CliHarness.Result result = cli().run("add", secret.toString());

            assertThat(result.exitCode()).isEqualTo(ExitCode.SANDBOX_VIOLATION.number());
        }
    }

    // ------------------------------------------------------------------ AC-2

    @Nested
    @DisplayName("AC-2: dry-run plans the change and makes none")
    class DryRun {

        @Test
        @DisplayName("branch delete --dry-run reports the ref and leaves it in place")
        void branchDeleteDryRun() throws IOException {
            repositoryWithOneCommit();
            assertThat(cli().run("branch", "create", "feature").succeeded()).isTrue();

            CliHarness.Result result = cli().run("--json", "branch", "delete", "feature", "--dry-run");

            assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.number());
            assertThat(result.out()).contains("\"mutated\": false");
            assertThat(result.out()).contains("refs/heads/feature");

            // The branch is still there afterwards, which is the actual claim.
            CliHarness.Result branches = cli().run("--json", "branch", "list");
            assertThat(branches.out()).contains("\"name\": \"feature\"");
        }

        @Test
        @DisplayName("commit --dry-run writes no object and moves no branch")
        void commitDryRunWritesNothing() throws IOException {
            repositoryWithOneCommit();
            Files.writeString(sandbox.resolve("b.txt"), "second\n");
            assertThat(cli().run("add", "b.txt").succeeded()).isTrue();

            String before = cli().run("--json", "log").out();
            CliHarness.Result result = cli().run("--json", "commit", "-m", "Would be", "--dry-run");

            assertThat(result.out()).contains("\"mutated\": false");
            // The history is byte-identical afterwards.
            assertThat(cli().run("--json", "log").out()).isEqualTo(before);
        }

        @Test
        @DisplayName("tag create --dry-run leaves no tag")
        void tagCreateDryRun() throws IOException {
            repositoryWithOneCommit();

            assertThat(cli().run("--json", "tag", "create", "v1", "--dry-run").out())
                    .contains("\"mutated\": false");
            assertThat(cli().run("--json", "tag", "list").out()).contains("\"count\": 0");
        }
    }

    // ------------------------------------------------------------------ AC-5

    @Nested
    @DisplayName("AC-5: JSON is byte-identical between runs over identical state")
    class DeterministicJson {

        @Test
        @DisplayName("the same command twice produces the same bytes")
        void repeatedRunsMatch() throws IOException {
            repositoryWithOneCommit();

            String first = cli().run("--json", "branch", "list").out();
            String second = cli().run("--json", "branch", "list").out();

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("the envelope carries a schema version and the command name")
        void envelopeShape() throws IOException {
            repositoryWithOneCommit();

            String out = cli().run("--json", "status").out();

            assertThat(out).contains("\"schemaVersion\": 1");
            assertThat(out).contains("\"command\": \"status\"");
            assertThat(out).contains("\"ok\": true");
            assertThat(out).contains("\"warnings\": []");
        }

        @Test
        @DisplayName("a failure envelope carries a code and no data")
        void failureShape() {
            CliHarness.Result result = cli().run("--json", "status");

            assertThat(result.exitCode()).isEqualTo(ExitCode.NOT_FOUND.number());
            assertThat(result.err()).contains("\"ok\": false");
            assertThat(result.err()).contains("\"code\": \"NOT_FOUND\"");
            assertThat(result.err()).doesNotContain("\"data\"");
        }
    }

    // ------------------------------------------------------------------ AC-6

    @Nested
    @DisplayName("AC-6: read-only refuses before the command runs")
    class ReadOnly {

        @Test
        @DisplayName("GITFORGE_READ_ONLY=1 refuses a commit and writes nothing")
        void environmentReadOnly() throws IOException {
            repositoryWithOneCommit();
            Files.writeString(sandbox.resolve("b.txt"), "second\n");
            assertThat(cli().run("add", "b.txt").succeeded()).isTrue();
            String before = cli().run("--json", "log").out();

            CliHarness.Result result = new CliHarness(sandbox, home)
                    .with("GITFORGE_READ_ONLY", "1")
                    .run("--json", "commit", "-m", "Nope");

            assertThat(result.exitCode()).isEqualTo(ExitCode.REFUSED.number());
            assertThat(result.err()).contains("READ_ONLY");
            assertThat(cli().run("--json", "log").out()).isEqualTo(before);
        }

        @Test
        @DisplayName("--read-only refuses the same way")
        void flagReadOnly() throws IOException {
            repositoryWithOneCommit();

            CliHarness.Result result = cli().run("--read-only", "branch", "create", "nope");

            assertThat(result.exitCode()).isEqualTo(ExitCode.REFUSED.number());
        }

        @Test
        @DisplayName("a flag cannot switch read-only off once the environment set it")
        void environmentWins() throws IOException {
            repositoryWithOneCommit();

            // There is no --no-read-only, deliberately: a safety setting a later
            // argument can clear is not a safety setting.
            CliHarness.Result result = new CliHarness(sandbox, home)
                    .with("GITFORGE_READ_ONLY", "1")
                    .run("branch", "create", "nope");

            assertThat(result.exitCode()).isEqualTo(ExitCode.REFUSED.number());
        }

        @Test
        @DisplayName("reading is still allowed")
        void readsStillWork() throws IOException {
            repositoryWithOneCommit();

            CliHarness.Result result = new CliHarness(sandbox, home)
                    .with("GITFORGE_READ_ONLY", "1")
                    .run("log");

            assertThat(result.succeeded()).isTrue();
        }
    }

    // ------------------------------------------------- destructive operations

    @Nested
    @DisplayName("destructive operations fail closed without consent")
    class Confirmation {

        @Test
        @DisplayName("a delete without a terminal and without --yes is refused")
        void refusesWithoutConsent() throws IOException {
            repositoryWithOneCommit();
            assertThat(cli().run("branch", "create", "feature").succeeded()).isTrue();

            // No console in a test JVM, so this is the non-interactive path.
            CliHarness.Result result = cli().run("--json", "branch", "delete", "feature");

            assertThat(result.exitCode()).isEqualTo(ExitCode.REFUSED.number());
            assertThat(result.err()).contains("CONFIRMATION_REQUIRED");
            assertThat(cli().run("--json", "branch", "list").out()).contains("feature");
        }

        @Test
        @DisplayName("--yes is consent given in advance")
        void yesConsents() throws IOException {
            repositoryWithOneCommit();
            assertThat(cli().run("branch", "create", "feature").succeeded()).isTrue();

            assertThat(cli().run("branch", "delete", "feature", "--yes").succeeded()).isTrue();
            assertThat(cli().run("--json", "branch", "list").out()).doesNotContain("feature");
        }

        @Test
        @DisplayName("--no-input never silently approves")
        void noInputFailsClosed() throws IOException {
            repositoryWithOneCommit();
            assertThat(cli().run("branch", "create", "feature").succeeded()).isTrue();

            CliHarness.Result result = cli().run("--no-input", "branch", "delete", "feature");

            assertThat(result.exitCode()).isEqualTo(ExitCode.REFUSED.number());
        }
    }
}
