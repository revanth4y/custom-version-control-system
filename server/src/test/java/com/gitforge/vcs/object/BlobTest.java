package com.gitforge.vcs.object;

import com.gitforge.vcs.GoldenVectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobTest {

    private static Blob blob(String content) {
        return new Blob(content.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("identity matches an independent implementation")
    class GoldenIdentity {

        @Test
        void helloWorld() {
            assertThat(blob("hello world").id().toHex()).isEqualTo(GoldenVectors.BLOB_HELLO_WORLD);
        }

        @Test
        void helloWorldWithTrailingNewline() {
            // A single extra byte must produce an entirely different id.
            assertThat(blob("hello world\n").id().toHex()).isEqualTo(GoldenVectors.BLOB_HELLO_WORLD_NEWLINE);
        }

        @Test
        void shortStrings() {
            assertThat(blob("abc").id().toHex()).isEqualTo(GoldenVectors.BLOB_ABC);
            assertThat(blob("a").id().toHex()).isEqualTo(GoldenVectors.BLOB_A);
            assertThat(blob("b").id().toHex()).isEqualTo(GoldenVectors.BLOB_B);
        }

        @Test
        void emptyBlob() {
            assertThat(new Blob(new byte[0]).id().toHex()).isEqualTo(GoldenVectors.EMPTY_BLOB);
        }

        @Test
        void binaryContentIncludingAnEmbeddedNul() {
            byte[] binary = {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};

            assertThat(new Blob(binary).id().toHex()).isEqualTo(GoldenVectors.BLOB_BINARY);
        }
    }

    @Test
    void identicalContentProducesIdenticalIds() {
        assertThat(blob("same content").id()).isEqualTo(blob("same content").id());
    }

    @Test
    void differentContentProducesDifferentIds() {
        assertThat(blob("content a").id()).isNotEqualTo(blob("content b").id());
    }

    @Test
    void aSingleFlippedByteChangesTheId() {
        byte[] original = {1, 2, 3, 4};
        byte[] altered = {1, 2, 3, 5};

        assertThat(new Blob(original).id()).isNotEqualTo(new Blob(altered).id());
    }

    @Test
    void lengthIsPartOfIdentitySoPaddingMatters() {
        assertThat(blob("ab").id()).isNotEqualTo(blob("ab ").id());
    }

    @Test
    void serializedFormCarriesTheExactHeaderBytes() {
        byte[] serialized = ObjectFormat.serialize(blob("hello world"));

        assertThat(new String(serialized, StandardCharsets.US_ASCII))
                .isEqualTo("blob 11\0hello world");
    }

    @Test
    void headerLengthCountsBytesNotCharacters() {
        // Four characters, but seven UTF-8 bytes.
        byte[] serialized = ObjectFormat.serialize(blob("héllo"));
        String header = new String(serialized, 0, 7, StandardCharsets.US_ASCII);

        assertThat("héllo".getBytes(StandardCharsets.UTF_8)).hasSize(6);
        assertThat(header).startsWith("blob 6\0");
    }

    @Test
    void payloadRoundTripsUnchanged() {
        byte[] content = {0, 1, 2, (byte) 0xFF};

        assertThat(new Blob(content).payload()).isEqualTo(content);
    }

    @Test
    void isImmutableAgainstCallerHeldArrays() {
        byte[] content = "original".getBytes(StandardCharsets.UTF_8);
        Blob subject = new Blob(content);
        ObjectId before = subject.id();

        content[0] = 'X';
        assertThat(subject.id()).isEqualTo(before);

        subject.payload()[0] = 'Y';
        assertThat(subject.payload()).isEqualTo("original".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void reportsItsTypeAndSize() {
        Blob subject = blob("12345");

        assertThat(subject.type()).isEqualTo(ObjectType.BLOB);
        assertThat(subject.size()).isEqualTo(5);
    }

    @Test
    void rejectsNullContent() {
        assertThatThrownBy(() -> new Blob(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityFollowsIdentity() {
        assertThat(blob("x")).isEqualTo(blob("x")).isNotEqualTo(blob("y"));
        assertThat(blob("x").hashCode()).isEqualTo(blob("x").hashCode());
    }
}
