package com.gitforge.cli.security;

import com.gitforge.cli.options.GlobalOptions;
import com.gitforge.cli.output.Json;
import com.gitforge.cli.output.Output;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a hostile name does when the CLI prints it.
 *
 * <p>Nearly everything the CLI writes to a terminal was written by somebody
 * else: a repository name, a branch, a commit message, an issue title and its
 * entire body, an error a server chose to return. A terminal does not merely
 * display what it is given, so those strings are instructions unless something
 * stops them being instructions.
 *
 * <p>What that buys is not code execution; it is a false screen. {@code ESC[2K}
 * erases the line just printed and {@code \r} goes back to its start, so a
 * repository description can delete the line naming the repository and print a
 * different one — that a private repository is public, that a deletion was
 * refused, that a fingerprint matched. {@code U+202E} reverses display order
 * without any control character at all, which is how a name can be spelled one
 * way and read another.
 *
 * <p>So these tests do not check for an exception. They check the exact bytes
 * that reach the stream, because the vulnerability is precisely that the bytes
 * looked fine to the program printing them.
 */
class TerminalOutputInjectionTest {

    private static final char ESC = 0x1B;

    /** What a hostile repository description might contain. */
    private static final String FORGERY =
            ESC + "[2K\rrepository: PUBLIC" + ESC + "[0m";

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    private Output plain() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        return new Output(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                GlobalOptions.parse(List.of(), Map.of()),
                new Redactor());
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * What was printed, without the terminator {@code println} adds.
     *
     * <p>On Windows that terminator is a carriage return and a newline, so a test
     * asserting no carriage return reached the stream would fail on the platform
     * rather than on the code. The claim is about the text, so the terminator is
     * removed before the claim is made.
     */
    private String printedLine() {
        String all = stdout();
        String terminator = System.lineSeparator();
        return all.endsWith(terminator) ? all.substring(0, all.length() - terminator.length()) : all;
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------- the neutraliser

    @Nested
    @DisplayName("characters a terminal would act on")
    class Neutralising {

        @ParameterizedTest(name = "U+{0}")
        @DisplayName("are made visible instead")
        @ValueSource(ints = {
                0x1B,   // escape - the start of every csi sequence
                0x0D,   // carriage return - go back and overwrite
                0x08,   // backspace
                0x07,   // bell
                0x00,   // null
                0x7F,   // delete
                0x9B,   // one-byte csi: a sequence with no escape character in it
                0x202E, // right-to-left override: the trojan source character
                0x202D, // left-to-right override
                0x2066, // first strong isolate
                0x200F, // right-to-left mark
        })
        void dangerousCharactersAreEscaped(int codePoint) {
            String text = "before" + (char) codePoint + "after";

            String safe = TerminalText.neutralise(text);

            assertThat(safe).doesNotContain(String.valueOf((char) codePoint));
            assertThat(safe).isEqualTo(
                    String.format("before<U+%04X>after", codePoint));
        }

        @Test
        @DisplayName("newline and tab are left alone, because real text uses them")
        void layoutSurvives() {
            assertThat(TerminalText.neutralise("a\nb\tc")).isEqualTo("a\nb\tc");
        }

        @Test
        @DisplayName("ordinary text, including emoji and accents, is untouched")
        void ordinaryTextIsUnchanged() {
            String text = "refactor: café ☕ 🚀 — naïve";

            assertThat(TerminalText.neutralise(text)).isSameAs(text);
        }

        @Test
        @DisplayName("a zero-width joiner survives, so emoji still render")
        void joinerSurvives() {
            // U+200D is a format character like the overrides, and unlike them it
            // cannot forge anything - it assembles emoji. Escaping the whole
            // Unicode category would have broken those for no gain.
            String family = "👩‍💻";

            assertThat(TerminalText.neutralise(family)).isEqualTo(family);
        }
    }

    // ---------------------------------------------------- through the CLI gate

    @Nested
    @DisplayName("a hostile string printed by the CLI")
    class ThroughOutput {

        @Test
        @DisplayName("cannot erase the line it was printed on")
        void cannotRewriteTheScreen() {
            plain().line("octocat/demo  " + FORGERY);

            assertThat(printedLine())
                    .as("neither an escape nor a carriage return reaches the stream")
                    .doesNotContain(String.valueOf(ESC))
                    .doesNotContain("\r");
            assertThat(printedLine()).contains("<U+001B>").contains("<U+000D>");
        }

        @Test
        @DisplayName("cannot do it through a warning either")
        void warningsAreAlsoNeutralised() {
            plain().warn("a warning from " + FORGERY);

            assertThat(stderr()).doesNotContain(String.valueOf(ESC + "[2K"));
            assertThat(stderr()).contains("<U+001B>");
        }

        @Test
        @DisplayName("nor through an error message a server chose")
        void failuresAreAlsoNeutralised() {
            plain().failure("repo view", "NOT_FOUND", "no such repository " + FORGERY);

            assertThat(stderr()).doesNotContain(String.valueOf(ESC) + "[2K");
            assertThat(stderr()).contains("<U+001B>");
        }

        @Test
        @DisplayName("and reordering characters cannot forge a name")
        void bidiIsNeutralised() {
            plain().line("repository: " + (char) 0x202E + "gpj.exe");

            assertThat(stdout()).doesNotContain(String.valueOf((char) 0x202E));
            assertThat(stdout()).contains("<U+202E>");
        }

        @Test
        @DisplayName("while ordinary output is unchanged")
        void ordinaryOutputIsUnchanged() {
            plain().line("octocat/demo  [PUBLIC]");

            assertThat(stdout()).isEqualTo("octocat/demo  [PUBLIC]" + System.lineSeparator());
        }

        @Test
        @DisplayName("and redaction still runs, before neutralising")
        void redactionStillHappens() {
            // Order matters: the redactor matches on the text as written. If
            // neutralising ran first it would rewrite the token's own characters
            // and the literal match could miss.
            Redactor redactor = new Redactor();
            redactor.remember("super-secret-token-value");
            out = new ByteArrayOutputStream();
            err = new ByteArrayOutputStream();
            Output output = new Output(
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8),
                    GlobalOptions.parse(List.of(), Map.of()),
                    redactor);

            output.line("token=super-secret-token-value " + FORGERY);

            assertThat(stdout()).doesNotContain("super-secret-token-value");
            assertThat(stdout()).doesNotContain(String.valueOf(ESC));
        }
    }

    // ------------------------------------------------------------ json output

    @Nested
    @DisplayName("the same string in JSON")
    class InJson {

        @Test
        @DisplayName("is escaped rather than emitted raw")
        void jsonEscapesDangerousCharacters() {
            String json = Json.write(Map.of("description", FORGERY));

            assertThat(json).doesNotContain(String.valueOf(ESC));
            assertThat(json).contains("\\u001b");
        }

        @Test
        @DisplayName("including the ones that are valid JSON but not safe to print")
        void escapesBeyondTheJsonMinimum() {
            // These are all legal inside a JSON string, which is why escaping
            // stopped before them, and all still acted on the terminal.
            String json = Json.write(List.of(
                    "del" + (char) 0x7F,
                    "csi" + (char) 0x9B,
                    "rtl" + (char) 0x202E));

            assertThat(json).contains("\\u007f").contains("\\u009b").contains("\\u202e");
            assertThat(json)
                    .doesNotContain(String.valueOf((char) 0x7F))
                    .doesNotContain(String.valueOf((char) 0x9B))
                    .doesNotContain(String.valueOf((char) 0x202E));
        }

        @Test
        @DisplayName("and ordinary values are still written exactly as before")
        void ordinaryJsonIsUnchanged() {
            assertThat(Json.write(Json.map("name", "demo", "count", 3)))
                    .isEqualTo("{\"name\":\"demo\",\"count\":3}");
        }
    }
}
