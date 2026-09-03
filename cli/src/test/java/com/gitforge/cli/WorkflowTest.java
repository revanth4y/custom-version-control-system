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
 * Whole journeys, not single commands.
 *
 * <p>A command that works alone can still be wrong in sequence: an index that is
 * not cleared, a working-tree state that is not recorded, a preview that
 * disagrees with the operation that follows it. Both of the real bugs found
 * while building this were of that kind and invisible to a unit test, so these
 * run the sequences a person actually runs and check the state between the
 * steps.
 */
class WorkflowTest {

    @TempDir
    Path sandbox;

    @TempDir
    Path home;

    private CliHarness cli() {
        return new CliHarness(sandbox, home);
    }

    private void commit(String path, String content, String message) throws IOException {
        Files.createDirectories(sandbox.resolve(path).getParent() == null
                ? sandbox : sandbox.resolve(path).getParent());
        Files.writeString(sandbox.resolve(path), content);
        assertThat(cli().run("add", path).succeeded()).isTrue();
        assertThat(cli().run("commit", "-m", message).succeeded()).isTrue();
    }

    @Nested
    @DisplayName("init through release")
    class ForwardJourney {

        @Test
        @DisplayName("init, add, commit, branch, merge, tag")
        void theWholeLifecycle() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();

            commit("README.md", "# Project\n", "Add the readme");
            commit("src/core.txt", "core\n", "Add the core");

            assertThat(cli().run("branch", "create", "feature").succeeded()).isTrue();
            assertThat(cli().run("switch", "feature").succeeded()).isTrue();
            commit("src/feature.txt", "feature\n", "Start the feature");

            assertThat(cli().run("switch", "main").succeeded()).isTrue();
            commit("CHANGELOG.md", "changes\n", "Note the changes");

            // Both sides have something the other lacks, so this must be a real
            // merge commit rather than a fast-forward.
            String preview = cli().run("--json", "merge", "feature", "--preview").out();
            assertThat(preview).contains("\"expectedOutcome\": \"MERGE_COMMIT\"");
            assertThat(preview).contains("\"mutated\": false");

            assertThat(cli().run("merge", "feature", "-m", "Merge the feature").succeeded()).isTrue();

            assertThat(cli().run("tag", "create", "v1.0.0", "-m", "First release").succeeded()).isTrue();
            String tags = cli().run("--json", "tag", "list").out();
            assertThat(tags).contains("\"annotated\": 1").contains("v1.0.0");

            // An annotated tag is an object that points at a commit, so peeling
            // it takes two steps rather than one.
            String chain = cli().run("--json", "tag", "chain", "v1.0.0").out();
            assertThat(chain).contains("\"length\": 2");

            String verify = cli().run("--json", "verify", "consistency").out();
            assertThat(verify).contains("\"consistent\": true");
        }

        @Test
        @DisplayName("the preview of a merge matches the merge that follows")
        void previewMatchesReality() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("base.txt", "base\n", "Base");
            assertThat(cli().run("branch", "create", "feature").succeeded()).isTrue();
            assertThat(cli().run("switch", "feature").succeeded()).isTrue();
            commit("f.txt", "feature\n", "Feature");
            assertThat(cli().run("switch", "main").succeeded()).isTrue();

            // main has nothing feature lacks, so the branch pointer can simply
            // move. This is the case an earlier version of the preview got wrong.
            assertThat(cli().run("--json", "merge", "feature", "--preview").out())
                    .contains("\"expectedOutcome\": \"FAST_FORWARD\"");
            assertThat(cli().run("--json", "merge", "feature").out())
                    .contains("\"outcome\": \"FAST_FORWARDED\"");
        }
    }

    @Nested
    @DisplayName("state carried between commands")
    class CarriedState {

        @Test
        @DisplayName("committing clears the index")
        void indexIsClearedAfterCommit() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "a\n", "First");

            assertThat(cli().run("--json", "status").out()).contains("\"staged\": []");
        }

        @Test
        @DisplayName("committing records what the working tree reflects, so switching works")
        void switchingWorksAfterCommit() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "a\n", "First");
            assertThat(cli().run("branch", "create", "other").succeeded()).isTrue();

            // Without the recorded tree state, checkout sees every committed file
            // as unsaved local work and refuses.
            CliHarness.Result result = cli().run("switch", "other");

            assertThat(result.succeeded())
                    .as("switch should not think committed files are local changes")
                    .isTrue();
        }

        @Test
        @DisplayName("a checkout that would overwrite local work is refused with a conflict")
        void localChangesBlockCheckout() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "a\n", "First");
            assertThat(cli().run("branch", "create", "other").succeeded()).isTrue();
            assertThat(cli().run("switch", "other").succeeded()).isTrue();
            commit("shared.txt", "from the other branch\n", "Add shared");
            assertThat(cli().run("switch", "main").succeeded()).isTrue();

            // An untracked file at a path the target branch also has. Switching
            // would replace it, which is what the engine refuses.
            Files.writeString(sandbox.resolve("shared.txt"), "unsaved local work\n");

            CliHarness.Result result = cli().run("switch", "other");

            assertThat(result.exitCode()).isEqualTo(ExitCode.CONFLICT.number());
            assertThat(Files.readString(sandbox.resolve("shared.txt")))
                    .isEqualTo("unsaved local work\n");
        }

        @Test
        @DisplayName("an untracked file the target branch does not have is left alone")
        void unrelatedUntrackedFilesDoNotBlock() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "a\n", "First");
            assertThat(cli().run("branch", "create", "other").succeeded()).isTrue();
            Files.writeString(sandbox.resolve("scratch.txt"), "notes\n");

            // Nothing would overwrite it, so refusing would be obstruction rather
            // than safety.
            assertThat(cli().run("switch", "other").succeeded()).isTrue();
            assertThat(sandbox.resolve("scratch.txt")).exists();
        }
    }

    @Nested
    @DisplayName("finding the repository")
    class Discovery {

        @Test
        @DisplayName("commands work from a subdirectory")
        void discoveryWalksUp() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("src/core.txt", "core\n", "First");
            Path nested = sandbox.resolve("src");

            CliHarness.Result result = cli().runIn(nested, "--json", "status");

            assertThat(result.succeeded()).isTrue();
            assertThat(result.out()).contains("\"branch\": \"main\"");
        }

        @Test
        @DisplayName("without a repository the message says what to do")
        void noRepositoryIsExplained() {
            CliHarness.Result result = cli().run("status");

            assertThat(result.exitCode()).isEqualTo(ExitCode.NOT_FOUND.number());
            assertThat(result.err()).contains("gitforge init");
        }

        @Test
        @DisplayName("repository metadata can never be committed")
        void metadataCannotBeTracked() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();

            // The repository must not be able to contain itself.
            CliHarness.Result result = cli().run("add", ".gitforge");

            assertThat(result.succeeded()).isFalse();
        }
    }

    @Nested
    @DisplayName("diagnostics")
    class Diagnostics {

        @Test
        @DisplayName("explain revision shows each resolution step")
        void explainRevisionTraces() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "one\n", "First");
            commit("b.txt", "two\n", "Second");

            String out = cli().run("--json", "explain", "revision", "main~1").out();

            assertThat(out).contains("\"kind\": \"BRANCH\"");
            assertThat(out).contains("\"kind\": \"ANCESTOR\"");
            assertThat(out).contains("\"resolved\"");
        }

        @Test
        @DisplayName("explain protection names every root by the reference it came from")
        void explainProtectionNamesRoots() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "one\n", "First");
            assertThat(cli().run("tag", "create", "v1", "-m", "One").succeeded()).isTrue();

            String out = cli().run("--json", "explain", "protection").out();

            assertThat(out).contains("branch main");
            assertThat(out).contains("tag v1");
        }

        @Test
        @DisplayName("explain rejection never queries a resource")
        void explainRejectionRevealsNothing() {
            // No repository, no server, no --repo: it explains the rules only.
            CliHarness.Result result = cli().run("--json", "explain", "rejection", "NOT_FOUND");

            assertThat(result.succeeded()).isTrue();
            assertThat(result.out()).contains("would reveal that a private repository exists");
        }

        @Test
        @DisplayName("verify integrity re-hashes every object")
        void verifyIntegrityChecksObjects() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "one\n", "First");

            String out = cli().run("--json", "verify", "integrity").out();

            assertThat(out).contains("\"integrity\": \"HEALTHY\"");
            assertThat(out).contains("\"damaged\": 0");
        }

        @Test
        @DisplayName("verify reachability reports without deleting anything")
        void verifyReachabilityDeletesNothing() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "one\n", "First");
            long before = countObjects();

            String out = cli().run("--json", "verify", "reachability").out();

            assertThat(out).contains("Unreachable objects are not deleted");
            assertThat(countObjects()).isEqualTo(before);
        }

        private long countObjects() throws IOException {
            Path objects = sandbox.resolve(".gitforge/repository/objects");
            if (!Files.isDirectory(objects)) {
                return 0;
            }
            try (var walk = Files.walk(objects)) {
                return walk.filter(Files::isRegularFile).count();
            }
        }
    }

    @Nested
    @DisplayName("output modes")
    class OutputModes {

        @Test
        @DisplayName("--quiet prints the identifier and nothing else")
        void quietPrintsOneValue() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "one\n", "First");

            CliHarness.Result result = cli().run("--quiet", "show");

            assertThat(result.out().strip()).hasSize(40);
        }

        @Test
        @DisplayName("--format renders the named fields")
        void formatRendersFields() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "one\n", "First");

            CliHarness.Result result = cli().run("--format={branch}", "status");

            assertThat(result.out().strip()).isEqualTo("main");
        }

        @Test
        @DisplayName("errors go to stderr even under --json")
        void errorsGoToStderr() {
            CliHarness.Result result = cli().run("--json", "status");

            // A script redirecting stdout to a file should still see the failure.
            assertThat(result.out()).isEmpty();
            assertThat(result.err()).contains("\"ok\": false");
        }
    }

    @Nested
    @DisplayName("the audit log")
    class Auditing {

        @Test
        @DisplayName("a successful command is recorded with the refs it moved")
        void successIsRecorded() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            commit("a.txt", "one\n", "First");

            String log = Files.readString(home.resolve(".gitforge/audit.jsonl"));

            assertThat(log).contains("\"command\":\"commit\"");
            assertThat(log).contains("refs/heads/main");
            assertThat(log).contains("\"exitCode\":0");
        }

        @Test
        @DisplayName("a refusal is recorded too")
        void refusalIsRecorded() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            cli().run("add", "../../../etc/passwd");

            String log = Files.readString(home.resolve(".gitforge/audit.jsonl"));

            // A refusal is exactly the kind of event a log exists to hold.
            assertThat(log).contains("\"exitCode\":6");
        }

        @Test
        @DisplayName("a credential-looking argument is redacted before it is written")
        void argumentsAreRedacted() throws IOException {
            assertThat(cli().run("init", ".").succeeded()).isTrue();
            cli().run("config", "set", "api.url", "https://user:hunter2@example.test/api/v1");

            String log = Files.readString(home.resolve(".gitforge/audit.jsonl"));

            assertThat(log).doesNotContain("hunter2");
        }
    }
}
