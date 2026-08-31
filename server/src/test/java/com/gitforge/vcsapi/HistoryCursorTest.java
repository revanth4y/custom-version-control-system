package com.gitforge.vcsapi;

import com.gitforge.common.error.BadRequestException;
import com.gitforge.vcs.GoldenVectors;
import com.gitforge.vcs.object.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Encoding, decoding and refusing cursors.
 *
 * <p>The refusals matter more than the round trip. A cursor that decodes wrongly
 * pages through the wrong history; one that is quietly ignored restarts the walk
 * and loops the client forever. Both are worse than an error.
 */
class HistoryCursorTest {

    private static final ObjectId START = ObjectId.fromHex(GoldenVectors.COMMIT_INITIAL);
    private static final ObjectId OTHER = ObjectId.fromHex(GoldenVectors.COMMIT_SECOND);

    private static String encoded(String payload) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void survivesEncodingAndDecoding() {
            HistoryCursor cursor = new HistoryCursor(START, "src/App.java", 30);

            assertThat(HistoryCursor.decode(cursor.encode())).isEqualTo(cursor);
        }

        @Test
        void carriesNoPathAsTheEmptyString() {
            HistoryCursor cursor = new HistoryCursor(START, "", 60);

            HistoryCursor decoded = HistoryCursor.decode(cursor.encode());

            assertThat(decoded.path()).isEmpty();
            assertThat(decoded.offset()).isEqualTo(60);
        }

        @Test
        void treatsANullPathAsNoPath() {
            assertThat(new HistoryCursor(START, null, 0).path()).isEmpty();
        }

        @Test
        void survivesAPathContainingTheSeparator() {
            // The path is encoded last precisely so its own colons cannot be
            // mistaken for field boundaries.
            HistoryCursor cursor = new HistoryCursor(START, "weird:name:file.txt", 10);

            assertThat(HistoryCursor.decode(cursor.encode())).isEqualTo(cursor);
        }

        @Test
        void isUrlSafe() {
            // It travels as a query parameter; padding and slashes would need
            // escaping every time.
            String text = new HistoryCursor(START, "a/b/c.txt", 90).encode();

            assertThat(text).doesNotContain("=", "+", "/");
        }

        @Test
        void advancesByWhatWasTaken() {
            HistoryCursor next = new HistoryCursor(START, "docs", 30).advancedBy(30);

            assertThat(next.offset()).isEqualTo(60);
            assertThat(next.start()).isEqualTo(START);
            assertThat(next.path()).isEqualTo("docs");
        }
    }

    @Nested
    @DisplayName("refused input")
    class Refused {

        @Test
        void refusesNothingAtAll() {
            assertThatThrownBy(() -> HistoryCursor.decode(null))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> HistoryCursor.decode("  "))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void refusesTextThatIsNotBase64() {
            assertThatThrownBy(() -> HistoryCursor.decode("not a cursor!!"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void refusesTooFewFields() {
            assertThatThrownBy(() -> HistoryCursor.decode(encoded("v1:" + START.toHex())))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void refusesAnUnrecognisedVersion() {
            // A future format must not decode plausibly under the old rules.
            assertThatThrownBy(() -> HistoryCursor.decode(encoded("v2:" + START.toHex() + ":0:")))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void refusesAMalformedStartCommit() {
            assertThatThrownBy(() -> HistoryCursor.decode(encoded("v1:not-a-sha:0:")))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void refusesANonNumericOffset() {
            assertThatThrownBy(() -> HistoryCursor.decode(encoded("v1:" + START.toHex() + ":far:")))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void refusesANegativeOffset() {
            assertThatThrownBy(() -> HistoryCursor.decode(encoded("v1:" + START.toHex() + ":-5:")))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void refusesToBeConstructedWithANegativeOffset() {
            assertThatThrownBy(() -> new HistoryCursor(START, "", -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void refusesToBeConstructedWithoutAStart() {
            assertThatThrownBy(() -> new HistoryCursor(null, "", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("belonging to its request")
    class Belonging {

        @Test
        void acceptsTheWalkItCameFrom() {
            HistoryCursor cursor = new HistoryCursor(START, "src", 30);

            cursor.requireMatches(START, "src");
        }

        @Test
        void refusesADifferentRevision() {
            /* The reason this check exists: without it, a cursor from one branch
               continues against another and the response is history for neither,
               with nothing in it to say so. */
            HistoryCursor cursor = new HistoryCursor(START, "", 30);

            assertThatThrownBy(() -> cursor.requireMatches(OTHER, ""))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("different revision");
        }

        @Test
        void refusesADifferentPathFilter() {
            HistoryCursor cursor = new HistoryCursor(START, "src", 30);

            assertThatThrownBy(() -> cursor.requireMatches(START, "docs"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("different path");
        }

        @Test
        void refusesAFilterAppearingPartWayThroughAnUnfilteredWalk() {
            HistoryCursor cursor = new HistoryCursor(START, "", 30);

            assertThatThrownBy(() -> cursor.requireMatches(START, "src"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void treatsANullRequestedPathAsNoFilter() {
            HistoryCursor cursor = new HistoryCursor(START, "", 0);

            cursor.requireMatches(START, null);
        }
    }
}
