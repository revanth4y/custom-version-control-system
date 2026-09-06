package com.gitforge.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What version the binary people actually run reports.
 *
 * <p>An integration test rather than a unit test because the thing under
 * examination does not exist until the jar is assembled, and the defect this
 * covers lived precisely in the gap between the two. {@code Cli.version()} reads
 * the manifest; the assembled jar had no {@code Implementation-Version} in its
 * manifest; so the shipped binary fell through to a hard-coded literal and
 * reported 2.0.15 for two releases running. Every unit test passed throughout,
 * because none of them ran the jar.
 *
 * <p>So this reads the manifest of the real artifact and runs the real command,
 * and checks the two agree. It does not name a version. Asserting the current
 * release here would need editing at every release and would be the same kind of
 * hard-coded value that caused the problem; what has to hold is that the binary
 * reports the version it was built from, whatever that is, and never a literal
 * baked into the source.
 */
class CliVersionIT {

    /** The literal the shipped binary used to report. It must never return. */
    private static final String STALE = "2.0.15";

    private static Path jar() {
        Path assembled = Path.of("target", "gitforge.jar");
        assertThat(assembled)
                .as("the assembled binary exists; this test runs after packaging")
                .exists();
        return assembled;
    }

    private static String manifestVersion() throws IOException {
        try (JarFile jar = new JarFile(jar().toFile())) {
            Manifest manifest = jar.getManifest();
            assertThat(manifest).as("the jar has a manifest").isNotNull();
            return manifest.getMainAttributes().getValue("Implementation-Version");
        }
    }

    @Nested
    @DisplayName("the assembled jar")
    class Assembled {

        @Test
        @DisplayName("carries the version it was built from")
        void carriesItsVersion() throws IOException {
            String version = manifestVersion();

            assertThat(version)
                    .as("Implementation-Version is written into the manifest by the build")
                    .isNotNull()
                    .isNotBlank();
            assertThat(version)
                    .as("and it is a version, not a placeholder")
                    .matches("\\d+\\.\\d+\\.\\d+.*");
        }

        @Test
        @DisplayName("does not carry the literal the source used to fall back to")
        void isNotTheStaleLiteral() throws IOException {
            assertThat(manifestVersion())
                    .as("the shipped binary reported " + STALE + " for two releases; not again")
                    .isNotEqualTo(STALE);
        }
    }

    @Nested
    @DisplayName("running the binary")
    class Running {

        private String run(String... arguments) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String[] command = new String[arguments.length + 3];
            command[0] = java;
            command[1] = "-jar";
            command[2] = jar().toString();
            System.arraycopy(arguments, 0, command, 3, arguments.length);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertThat(process.waitFor(2, TimeUnit.MINUTES))
                    .as("the command finished")
                    .isTrue();
            assertThat(process.exitValue()).as("exit code, for: " + String.join(" ", arguments))
                    .isZero();
            return output.strip();
        }

        @Test
        @DisplayName("reports the version in its own manifest")
        void reportsTheBuiltVersion() throws Exception {
            String expected = manifestVersion();

            assertThat(run("--version"))
                    .as("what a user sees is what the build put in the jar")
                    .isEqualTo("gitforge " + expected);
        }

        @Test
        @DisplayName("never reports the stale literal")
        void neverReportsTheStaleLiteral() throws Exception {
            assertThat(run("--version"))
                    .as("the defect this test exists for")
                    .doesNotContain(STALE);
        }

        @Test
        @DisplayName("--json still answers with the same version, in the envelope")
        void jsonIsUnchanged() throws Exception {
            String expected = manifestVersion();
            String json = run("--json", "--version");

            assertThat(json)
                    .contains("\"version\"")
                    .contains(expected)
                    .doesNotContain(STALE);
            assertThat(json.startsWith("{")).as("still an envelope: " + json).isTrue();
        }

        @Test
        @DisplayName("--quiet still suppresses the human line")
        void quietIsUnchanged() throws Exception {
            assertThat(run("--quiet", "--version"))
                    .as("quiet prints nothing for a command whose only output is a line")
                    .isEmpty();
        }
    }
}
