package com.gitforge.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies the stateless access tokens used for authentication.
 *
 * <p>The token subject is the user's UUID rather than the username, so renaming
 * an account cannot invalidate or misdirect a live token.
 */
@Service
public class JwtService {

    /** HS256 requires at least 256 bits of key material. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final Duration expiry;
    private final String issuer;
    private final Clock clock;

    @Autowired
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Visible for testing, so expiry can be exercised against a fixed clock. */
    JwtService(JwtProperties properties, Clock clock) {
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not configured. Set it to a random value of at least "
                            + MIN_SECRET_BYTES + " characters before starting the server.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes; got " + keyBytes.length + ".");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expiry = properties.expiry();
        this.issuer = properties.issuer();
        this.clock = clock;
    }

    /** Issues a signed access token identifying {@code userId}. */
    public String issueToken(UUID userId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns the user id carried by a valid token, or empty if the token is
     * malformed, expired, or not signed by this server.
     */
    public Optional<UUID> extractUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException ex) {
            // Any verification failure is treated uniformly as "not authenticated";
            // distinguishing causes here would leak information to callers.
            return Optional.empty();
        }
    }

    public Duration getExpiry() {
        return expiry;
    }
}
