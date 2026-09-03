package com.gitforge.cli;

import com.gitforge.cli.command.Registry;
import com.gitforge.cli.config.CliConfig;
import com.gitforge.cli.config.Credentials;
import com.gitforge.cli.options.GlobalOptions;
import com.gitforge.cli.output.Format;
import com.gitforge.cli.output.Json;
import com.gitforge.cli.security.Redactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing, rendering, and the vocabulary between the CLI and a script.
 *
 * <p>These are the parts a person never looks at directly and a pipeline depends
 * on entirely: which argument belongs to which flag, what a number looks like
 * when written twice, and what the shell learns from the exit status.
 */
class CommandLineTest {

    @Nested
    @DisplayName("global flags")
    class Flags {

        private GlobalOptions parse(String... args) {
            return GlobalOptions.parse(List.of(args), Map.of());
        }

        @Test
        @DisplayName("a flag's value may be attached or separate")
        void bothValueFormsWork() {
            assertThat(parse("--timeout", "45").timeoutSeconds()).isEqualTo(45);
            assertThat(parse("--timeout=45").timeoutSeconds()).isEqualTo(45);
        }

        @Test
        @DisplayName("everything after -- is an argument, whatever it looks like")
        void doubleDashEndsFlagParsing() {
            GlobalOptions options = parse("add", "--", "--json", "--read-only");

            // Without this, a file could not be named --json.
            assertThat(options.json()).isFalse();
            assertThat(options.readOnly()).isFalse();
            assertThat(options.positional()).containsExactly("add", "--json", "--read-only");
        }

        @Test
        @DisplayName("an unknown long flag is passed to the command, not guessed at")
        void unknownFlagsReachTheCommand() {
            assertThat(parse("tag", "create", "v1", "--message", "hello").positional())
                    .contains("--message", "hello");
        }

        @Test
        @DisplayName("a flag missing its value is a usage error")
        void missingValueIsRefused() {
            assertThatThrownBy(() -> parse("--timeout"))
                    .isInstanceOf(CliException.class)
                    .satisfies(thrown ->
                            assertThat(((CliException) thrown).exitCode()).isEqualTo(ExitCode.USAGE));
        }

        @Test
        @DisplayName("a timeout must be a positive whole number")
        void timeoutIsValidated() {
            assertThatThrownBy(() -> parse("--timeout", "0")).isInstanceOf(CliException.class);
            assertThatThrownBy(() -> parse("--timeout", "-5")).isInstanceOf(CliException.class);
            assertThatThrownBy(() -> parse("--timeout", "soon")).isInstanceOf(CliException.class);
        }

        @Test
        @DisplayName("the environment supplies defaults")
        void environmentDefaults() {
            GlobalOptions options = GlobalOptions.parse(
                    List.of("status"),
                    Map.of("GITFORGE_READ_ONLY", "1", "GITFORGE_JSON", "true", "CI", "true"));

            assertThat(options.readOnly()).isTrue();
            assertThat(options.json()).isTrue();
            // A CI runner has no terminal, so prompting there can only hang.
            assertThat(options.noInput()).isTrue();
        }

        @Test
        @DisplayName("NO_COLOR is honoured however it is set")
        void noColourConvention() {
            assertThat(GlobalOptions.parse(List.of(), Map.of("NO_COLOR", "")).noColor()).isTrue();
        }
    }

    @Nested
    @DisplayName("JSON is written the same way twice")
    class Determinism {

        @Test
        @DisplayName("field order follows insertion, not hashing")
        void insertionOrderIsPreserved() {
            String written = Json.write(Json.map("zebra", 1, "apple", 2, "mango", 3));

            assertThat(written).isEqualTo("{\"zebra\":1,\"apple\":2,\"mango\":3}");
        }

        @Test
        @DisplayName("timestamps are RFC 3339 in UTC, whatever the machine's zone")
        void timestampsAreUtc() {
            assertThat(Json.time(Instant.parse("2026-09-03T12:34:56Z")))
                    .isEqualTo("2026-09-03T12:34:56Z");
        }

        @Test
        @DisplayName("numbers carry no locale and no exponent")
        void numbersAreLocaleFree() {
            assertThat(Json.write(Json.map("ratio", 0.142857142857)))
                    .isEqualTo("{\"ratio\":0.142857}");
            assertThat(Json.write(Json.map("tiny", 0.0000001))).doesNotContain("E");
        }

        @Test
        @DisplayName("control characters are escaped rather than emitted raw")
        void controlCharactersAreEscaped() {
            String written = Json.write(Json.map("text", "linebreak\n"));

            assertThat(written).contains("\\u0001").contains("\\n");
        }

        @Test
        @DisplayName("the same value written twice is byte-identical")
        void repeatedWritesMatch() {
            Map<String, Object> value = Json.map(
                    "list", List.of(1, 2, 3), "nested", Json.map("a", true, "b", (Object) null));

            assertThat(Json.write(value)).isEqualTo(Json.write(value));
        }
    }

    @Nested
    @DisplayName("--format")
    class Formatting {

        @Test
        @DisplayName("a field is substituted by name")
        void substitutesFields() {
            assertThat(Format.apply("{commit} on {branch}",
                    Json.map("commit", "abc123", "branch", "main")))
                    .isEqualTo("abc123 on main");
        }

        @Test
        @DisplayName("a dotted path walks into nested objects")
        void walksNestedFields() {
            assertThat(Format.apply("{a.b}", Json.map("a", Json.map("b", "deep"))))
                    .isEqualTo("deep");
        }

        @Test
        @DisplayName("an unknown field is refused rather than left blank")
        void unknownFieldIsRefused() {
            // A template that silently produced nothing would let a script keep
            // running with a blank where a commit id should be.
            assertThatThrownBy(() -> Format.apply("{nope}", Json.map("commit", "abc")))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("nope");
        }

        @Test
        @DisplayName("an unclosed brace is a usage error")
        void unclosedBraceIsRefused() {
            assertThatThrownBy(() -> Format.apply("{commit", Json.map("commit", "abc")))
                    .isInstanceOf(CliException.class);
        }
    }

    @Nested
    @DisplayName("exit codes")
    class ExitCodes {

        @Test
        @DisplayName("every code is distinct and stable")
        void codesAreDistinct() {
            List<Integer> numbers = java.util.Arrays.stream(ExitCode.values())
                    .map(ExitCode::number).toList();

            assertThat(numbers).doesNotHaveDuplicates();
            // Pinned: a pipeline branching on 4 must keep meaning "forbidden".
            assertThat(ExitCode.SUCCESS.number()).isZero();
            assertThat(ExitCode.USAGE.number()).isEqualTo(2);
            assertThat(ExitCode.NOT_FOUND.number()).isEqualTo(3);
            assertThat(ExitCode.FORBIDDEN.number()).isEqualTo(4);
            assertThat(ExitCode.REFUSED.number()).isEqualTo(5);
            assertThat(ExitCode.SANDBOX_VIOLATION.number()).isEqualTo(6);
            assertThat(ExitCode.CONFLICT.number()).isEqualTo(7);
            assertThat(ExitCode.REMOTE_TRANSFER.number()).isEqualTo(8);
            assertThat(ExitCode.VERIFICATION_FAILED.number()).isEqualTo(9);
            assertThat(ExitCode.TIMEOUT.number()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("the command registry")
    class Commands {

        @Test
        @DisplayName("every group in the taxonomy is present")
        void taxonomyIsComplete() {
            List<String> names = Registry.names();

            assertThat(names).contains(
                    "init", "clone", "status", "log", "show", "diff", "add", "commit", "merge",
                    "switch",
                    "branch list", "branch show", "branch create", "branch delete",
                    "branch rename", "branch compare",
                    "tag list", "tag show", "tag create", "tag delete", "tag peel", "tag chain",
                    "remote list", "remote add", "remote remove", "remote show",
                    "remote fetch", "remote push", "remote pull", "remote diagnose",
                    "repo list", "repo show", "repo create", "repo delete",
                    "repo visibility", "repo refs",
                    "release list", "release show", "release create", "release edit", "release delete",
                    "issue list", "issue show", "issue create", "issue edit",
                    "issue close", "issue reopen", "issue comment",
                    "insights overview", "insights activity", "insights commits",
                    "insights contributors", "insights refs", "insights storage", "insights health",
                    "verify integrity", "verify reachability", "verify refs",
                    "verify tags", "verify consistency",
                    "explain revision", "explain reachability", "explain rejection",
                    "explain push", "explain protection",
                    "sandbox status", "sandbox init", "sandbox verify", "sandbox policy",
                    "config get", "config set", "config list", "config unset",
                    "auth login", "auth logout", "auth status", "auth token");
        }

        @Test
        @DisplayName("a group name alone suggests its subcommands")
        void groupNameSuggests() {
            assertThatThrownBy(() -> Registry.find(List.of("branch")))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("branch list");
        }

        @Test
        @DisplayName("an unknown command points at help")
        void unknownCommandIsRefused() {
            assertThatThrownBy(() -> Registry.find(List.of("teleport")))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("gitforge help");
        }

        @Test
        @DisplayName("the longest matching path wins")
        void longestMatchWins() {
            Registry.Match match = Registry.find(List.of("branch", "create", "feature"));

            assertThat(match.command().name()).isEqualTo("branch create");
            assertThat(match.arguments()).containsExactly("feature");
        }

        @Test
        @DisplayName("read-only commands are not declared as mutating")
        void readCommandsDoNotMutate() {
            // If this drifts, read-only mode starts refusing things it should
            // allow — or worse, allowing things it should refuse.
            for (String name : List.of("status", "log", "show", "diff", "branch list",
                    "tag list", "verify integrity", "explain revision", "sandbox policy")) {
                assertThat(Registry.find(List.of(name.split(" "))).command().mutates())
                        .as(name + " must not be declared as mutating")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("writing commands are declared as mutating")
        void writeCommandsMutate() {
            for (String name : List.of("init", "add", "commit", "merge", "switch",
                    "branch create", "branch delete", "tag create", "tag delete",
                    "remote add", "remote push", "repo create", "issue create")) {
                assertThat(Registry.find(List.of(name.split(" "))).command().mutates())
                        .as(name + " must be declared as mutating")
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("configuration and credentials")
    class Storage {

        @Test
        @DisplayName("an unknown setting is refused rather than silently ignored")
        void unknownSettingIsRefused(@TempDir Path home) {
            CliConfig config = new CliConfig(home.resolve("config"));

            assertThatThrownBy(() -> config.set("api.urll", "http://x"))
                    .isInstanceOf(CliException.class)
                    .hasMessageContaining("Unknown setting");
        }

        @Test
        @DisplayName("settings survive a round trip and are sorted on disk")
        void settingsRoundTrip(@TempDir Path home) {
            Path file = home.resolve("config");
            CliConfig first = new CliConfig(file);
            first.set("sandbox.root", "/srv/sandbox");
            first.set("api.url", "http://localhost:8080/api/v1");

            CliConfig reloaded = new CliConfig(file);
            assertThat(reloaded.get("api.url")).contains("http://localhost:8080/api/v1");
            assertThat(reloaded.all().keySet()).containsExactly("api.url", "sandbox.root");
        }

        @Test
        @DisplayName("a stored token is registered for redaction the moment it is read")
        void storedTokensAreRedacted(@TempDir Path home) {
            Redactor redactor = new Redactor();
            Credentials credentials = new Credentials(home.resolve("credentials"), redactor);
            credentials.store("example.test", "a-very-secret-token-value");

            assertThat(redactor.scrub("token is a-very-secret-token-value"))
                    .doesNotContain("a-very-secret-token-value");
        }

        @Test
        @DisplayName("describing credentials never includes a token")
        void describeOmitsTokens(@TempDir Path home) {
            Credentials credentials = new Credentials(
                    home.resolve("credentials"), new Redactor());
            credentials.store("example.test", "a-very-secret-token-value");

            assertThat(Json.write(credentials.describe()))
                    .contains("example.test")
                    .doesNotContain("a-very-secret-token-value");
        }

        @Test
        @DisplayName("an empty token is refused")
        void emptyTokenIsRefused(@TempDir Path home) {
            Credentials credentials = new Credentials(home.resolve("credentials"), new Redactor());

            assertThatThrownBy(() -> credentials.store("example.test", "  "))
                    .isInstanceOf(CliException.class);
        }
    }
}
