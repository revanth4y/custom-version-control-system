package com.gitforge.vcsapi;

import com.gitforge.vcs.object.FileMode;
import com.gitforge.vcs.repository.FileChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one figure that bounds a file, and where the line falls.
 *
 * <p>The value is shared by the paths that write a file and the path that reads
 * one back, which is what stops a repository holding something the API will not
 * return. These tests pin the boundary itself: off by one here and either a file
 * the API accepted becomes unreadable, or the limit stops being reachable.
 */
class ContentLimitsTest {

    private static final int LIMIT = ContentLimits.MAX_BLOB_BYTES;

    @Nested
    @DisplayName("where the line falls")
    class Boundary {

        @Test
        void justUnderTheLimitIsAllowed() {
            assertThat(ContentLimits.withinBlobLimit(LIMIT - 1)).isTrue();
        }

        @Test
        void exactlyTheLimitIsAllowed() {
            // Inclusive on purpose: "at most ten megabytes" has to accept a file
            // of exactly ten megabytes, or the number in the message is a lie.
            assertThat(ContentLimits.withinBlobLimit(LIMIT)).isTrue();
        }

        @Test
        void oneByteOverIsRefused() {
            assertThat(ContentLimits.withinBlobLimit(LIMIT + 1)).isFalse();
        }

        @Test
        void nothingIsAllowed() {
            assertThat(ContentLimits.withinBlobLimit(0)).isTrue();
        }

        @Test
        void takesALongSoAnOversizedCountCannotWrapIntoRange() {
            /* Measured as a long throughout. Summing sizes into an int is how a
               large enough total comes back negative and passes a check it
               should have failed. */
            assertThat(ContentLimits.withinBlobLimit(Long.MAX_VALUE)).isFalse();
        }
    }

    @Nested
    @DisplayName("measuring a change without copying it")
    class Measuring {

        @Test
        void reportsThePayloadLength() {
            assertThat(FileChange.put("a.txt", new byte[1234], FileMode.REGULAR_FILE).size())
                    .isEqualTo(1234);
        }

        @Test
        void aDeletionWritesNothing() {
            assertThat(FileChange.delete("gone.txt").size()).isZero();
        }

        @Test
        void agreesWithTheContentItWouldHandOut() {
            FileChange.Put change = new FileChange.Put("a.txt", new byte[4096], FileMode.REGULAR_FILE);

            assertThat(change.size()).isEqualTo(change.content().length);
        }
    }

    @Nested
    @DisplayName("the limit counts stored bytes, whichever encoding carried them")
    class Encodings {

        /**
         * Both encodings are measured after decoding, so the same file is judged
         * the same way however it was sent. Measuring the base64 text instead
         * would refuse a file at three quarters of the limit, purely for having
         * been sent as binary.
         */
        @Test
        void base64AndUtf8AgreeOnTheSameBytes() {
            byte[] raw = new byte[3_000];
            String asBase64 = Base64.getEncoder().encodeToString(raw);
            String asText = new String(new byte[3_000], StandardCharsets.ISO_8859_1);

            assertThat(ContentApiService.decode(asBase64, "base64")).hasSize(3_000);
            // Latin-1 code points above 0x7F take two bytes in UTF-8; this string
            // is all NULs, so its byte count is its length.
            assertThat(ContentApiService.decode(asText, "utf-8")).hasSize(3_000);
        }

        @Test
        void base64ExpandsOnTheWireButNotInTheStore() {
            /* A file at the limit arrives as roughly a third more base64 text.
               That is the transport's problem, not the limit's: what is stored
               and later served is the decoded length. */
            byte[] atLimit = new byte[LIMIT];
            int encodedLength = Base64.getEncoder().encodeToString(atLimit).length();

            assertThat(encodedLength).isGreaterThan(LIMIT);
            assertThat(ContentLimits.withinBlobLimit(atLimit.length)).isTrue();
        }
    }
}
