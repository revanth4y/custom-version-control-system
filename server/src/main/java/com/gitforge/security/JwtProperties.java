package com.gitforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT signing configuration, bound from {@code gitforge.jwt.*}.
 *
 * <p>The secret is deliberately not defaulted: a build-time fallback would risk
 * shipping a known signing key. {@link JwtService} rejects an absent or
 * undersized secret at startup.
 */
@ConfigurationProperties(prefix = "gitforge.jwt")
public record JwtProperties(String secret, Duration expiry, String issuer) {
}
