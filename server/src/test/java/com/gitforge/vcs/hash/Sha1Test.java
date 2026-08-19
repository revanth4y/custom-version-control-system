package com.gitforge.vcs.hash;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SHA-1 checked against the published FIPS 180 vectors.
 *
 * <p>These fix the foundation: if the digest itself were wrong, every object id
 * in the system would be wrong in a way no round-trip test could reveal.
 */
class Sha1Test {

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @ParameterizedTest(name = "sha1(\"{0}\") = {1}")
    @CsvSource({
            "'',                                                                 da39a3ee5e6b4b0d3255bfef95601890afd80709",
            "abc,                                                                a9993e364706816aba3e25717850c26c9cd0d89d",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq,           84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            "The quick brown fox jumps over the lazy dog,                        2fd4e1c67a2d28fced849ee1bb76e7391b93eb12"
    })
    @DisplayName("matches the published FIPS 180 test vectors")
    void matchesKnownVectors(String input, String expected) {
        assertThat(hex(Sha1.hash(ascii(input)))).isEqualTo(expected);
    }

    @Test
    void hashOfEmptyInputIsTheWellKnownEmptyDigest() {
        assertThat(hex(Sha1.hash(new byte[0]))).isEqualTo("da39a3ee5e6b4b0d3255bfef95601890afd80709");
    }

    @Test
    void digestIsAlwaysTwentyBytes() {
        assertThat(Sha1.hash(new byte[0])).hasSize(20);
        assertThat(Sha1.hash(new byte[1_000])).hasSize(Sha1.HASH_LENGTH);
    }

    @Test
    void incrementalUpdatesAgreeWithASingleCall() {
        byte[] whole = ascii("The quick brown fox jumps over the lazy dog");

        MessageDigest digest = Sha1.newDigest();
        digest.update(ascii("The quick brown fox "));
        digest.update(ascii("jumps over the lazy dog"));

        assertThat(hex(digest.digest())).isEqualTo(hex(Sha1.hash(whole)));
    }

    @Test
    void eachCallReturnsAnIndependentDigestInstance() {
        // A shared MessageDigest would carry state between callers.
        assertThat(Sha1.newDigest()).isNotSameAs(Sha1.newDigest());
    }

    @Test
    void handlesBinaryInputIncludingNulBytes() {
        byte[] binary = {0, 1, 2, (byte) 0xFF, (byte) 0xFE, 0};

        assertThat(Sha1.hash(binary)).hasSize(20);
        assertThat(hex(Sha1.hash(binary))).isNotEqualTo(hex(Sha1.hash(new byte[]{0, 1, 2})));
    }
}
