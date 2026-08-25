package com.gitforge.demo;

import com.gitforge.vcs.GoldenVectors;
import com.gitforge.vcs.object.Blob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The demonstration fixtures that exist to be looked at, rather than asserted on
 * elsewhere.
 *
 * <p>Two files in {@code diff-demo} are there for the blob view's awkward
 * states: one with no contents, and one long enough that the view stops
 * numbering its lines. Neither state could be reached from the demo data before
 * them, so both had been written and never seen.
 *
 * <p>What is checked here is that they stay what they claim to be. A fixture
 * whose size or line count drifts silently stops testing the thing it was added
 * for - a file that fell one line short of the threshold would still render, and
 * the numbering fallback it exists to exercise would simply never run.
 */
class DemoDatasetTest {

    /** The blob view stops numbering past this many lines. */
    private static final int NUMBERING_THRESHOLD = 5_000;

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("the many-line fixture")
    class ManyLines {

        private final String content = DemoDataset.numberedLines(5_001);

        @Test
        void hasExactlyTheLineCountItClaims() {
            assertThat(content.lines()).hasSize(5_001);
        }

        @Test
        void crossesTheNumberingThreshold() {
            /* The point of the fixture. One line over is deliberate: it is the
               smallest file that reaches the fallback, and stating the
               relationship here means raising the threshold cannot quietly
               strand the fixture below it. */
            assertThat(content.lines().count()).isGreaterThan(NUMBERING_THRESHOLD);
        }

        @Test
        void readsFromTheFirstLineToTheLast() {
            assertThat(content).startsWith("line 1\n").endsWith("line 5001\n");
        }

        @Test
        void endsWithANewlineSoTheLastLineIsNotPhantom() {
            // A file ending without one renders a final line the file does not
            // have, at both ends of the stack.
            assertThat(content).endsWith("\n");
        }

        @Test
        void isExactlyTheSizeTheFixtureIsDocumentedAs() {
            assertThat(bytes(content)).hasSize(48_903);
        }

        @Test
        void hashesToAFixedObjectId() {
            /* Content addressing means the id follows from the bytes alone, with
               no dependence on when the dataset was seeded. If this changes, the
               content changed. */
            assertThat(new Blob(bytes(content)).id().toHex())
                    .isEqualTo("5297c048538516f861cf135923d87386d14b2ecb");
        }

        @Test
        void isTheSameBytesEveryTime() {
            assertThat(DemoDataset.numberedLines(5_001)).isEqualTo(content);
        }
    }

    @Nested
    @DisplayName("the empty fixture")
    class Empty {

        @Test
        void hashesToTheWellKnownEmptyBlob() {
            /* Not a fixture-specific number: this is the id every implementation
               of the format produces for no content at all, which is the format
               being exactly as advertised. */
            assertThat(new Blob(new byte[0]).id().toHex()).isEqualTo(GoldenVectors.EMPTY_BLOB);
        }

        @Test
        void hasNoLines() {
            assertThat("".lines()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the generator")
    class Generator {

        @Test
        void producesNothingForNoLines() {
            assertThat(DemoDataset.numberedLines(0)).isEmpty();
        }

        @Test
        void numbersFromOneRatherThanZero() {
            assertThat(DemoDataset.numberedLines(3)).isEqualTo("line 1\nline 2\nline 3\n");
        }
    }
}
