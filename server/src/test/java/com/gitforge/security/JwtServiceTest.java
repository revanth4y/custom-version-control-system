package com.gitforge.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for token issuance and verification. No Spring context is started:
 * the clock is injected so expiry can be tested without sleeping.
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256!";
    private static final String OTHER_SECRET = "a-completely-different-secret-of-sufficient-length";
    private static final Duration EXPIRY = Duration.ofHours(1);
    private static final String ISSUER = "gitforge";

    private static JwtService serviceAt(Instant now, String secret) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new JwtService(new JwtProperties(secret, EXPIRY, ISSUER), clock);
    }

    private static JwtService serviceAt(Instant now) {
        return serviceAt(now, SECRET);
    }

    @Test
    void issuedTokenRoundTripsToTheSameUserId() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        JwtService service = serviceAt(now);
        UUID userId = UUID.randomUUID();

        String token = service.issueToken(userId);

        assertThat(service.extractUserId(token)).contains(userId);
    }

    @Test
    void tokenIsRejectedAfterItExpires() {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        String token = serviceAt(issuedAt).issueToken(UUID.randomUUID());

        // One second past the one-hour lifetime.
        JwtService later = serviceAt(issuedAt.plus(EXPIRY).plusSeconds(1));

        assertThat(later.extractUserId(token)).isEmpty();
    }

    @Test
    void tokenIsStillValidJustBeforeExpiry() {
        Instant issuedAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID userId = UUID.randomUUID();
        String token = serviceAt(issuedAt).issueToken(userId);

        JwtService justBefore = serviceAt(issuedAt.plus(EXPIRY).minusSeconds(1));

        assertThat(justBefore.extractUserId(token)).contains(userId);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String foreignToken = serviceAt(now, OTHER_SECRET).issueToken(UUID.randomUUID());

        assertThat(serviceAt(now).extractUserId(foreignToken)).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        JwtService service = serviceAt(now);
        String token = service.issueToken(UUID.randomUUID());

        // Flip the final character of the signature.
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(service.extractUserId(tampered)).isEmpty();
    }

    @Test
    void malformedTokenIsRejected() {
        JwtService service = serviceAt(Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(service.extractUserId("not-a-jwt")).isEmpty();
        assertThat(service.extractUserId("")).isEmpty();
    }

    @Test
    void missingSecretFailsFastAtConstruction() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("", EXPIRY, ISSUER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is not configured");
    }

    @Test
    void shortSecretFailsFastAtConstruction() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("too-short", EXPIRY, ISSUER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
