package com.gitforge.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down credential guessing against the authentication endpoints.
 *
 * <p>Without this the login endpoint accepted seventeen attempts a second
 * indefinitely. BCrypt makes each attempt cost something, but not enough:
 * a password worth guessing is still guessable at that rate.
 *
 * <p>Only failures are counted, and a success clears the caller's record, so
 * someone who mistypes a password twice and then gets it right is never worse
 * off. Login and signup share one counter because a rejected signup reveals
 * whether an address is already registered - the same oracle a failed login
 * gives, reached from a different door.
 *
 * <p>The table is bounded. An unbounded map keyed by remote address is itself a
 * way to exhaust the server's memory, so entries expire and the table is capped;
 * see {@link #evictIfOverCapacity()} for what happens when it fills.
 */
@Component
public class AuthAttemptLimiter {

    /** Long enough to make guessing pointless, short enough to forgive a bad afternoon. */
    public static final Duration WINDOW = Duration.ofMinutes(15);

    /** A person mistypes two or three times; ten leaves room for a shared address. */
    public static final int MAX_FAILURES = 10;

    /** About 640 KB of entries. Reached only under deliberate abuse. */
    static final int MAX_TRACKED_ADDRESSES = 10_000;

    private final ConcurrentHashMap<String, Attempts> byAddress = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public AuthAttemptLimiter() {
        this(Clock.systemUTC());
    }

    /** Visible for testing, so a window can be exercised without waiting for one. */
    AuthAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** One address's failures, and when the window holding them began. */
    private record Attempts(int failures, Instant windowStart, Instant lastSeen) {
    }

    /**
     * How long the caller must wait, or zero if they may proceed.
     *
     * <p>Returning the remaining time rather than a boolean is what lets the
     * response carry {@code Retry-After}: "no" is much less useful than "not for
     * another four minutes".
     */
    public Duration retryAfter(String address) {
        Attempts attempts = byAddress.get(address);
        if (attempts == null || expired(attempts)) {
            return Duration.ZERO;
        }
        if (attempts.failures() < MAX_FAILURES) {
            return Duration.ZERO;
        }
        Duration remaining = Duration.between(clock.instant(), attempts.windowStart().plus(WINDOW));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public boolean isBlocked(String address) {
        return !retryAfter(address).isZero();
    }

    /**
     * Records a failed attempt.
     *
     * <p>Uses {@code compute} rather than get-then-put: two requests from one
     * address arriving together must not each read the same count and write back
     * the same increment, which would let a determined caller have twice the
     * attempts they are entitled to.
     */
    public void recordFailure(String address) {
        Instant now = clock.instant();
        byAddress.compute(address, (key, existing) -> {
            if (existing == null || expired(existing)) {
                return new Attempts(1, now, now);
            }
            return new Attempts(existing.failures() + 1, existing.windowStart(), now);
        });
        evictIfOverCapacity();
    }

    /** Authentication succeeded, so the failures that preceded it no longer count. */
    public void recordSuccess(String address) {
        byAddress.remove(address);
    }

    private boolean expired(Attempts attempts) {
        return clock.instant().isAfter(attempts.windowStart().plus(WINDOW));
    }

    /**
     * Keeps the table bounded.
     *
     * <p>Expired entries go first. If that is not enough the oldest are dropped,
     * rather than clearing the table wholesale - a full reset would hand an
     * attacker a way to wipe their own record by flooding the map from other
     * addresses.
     */
    private void evictIfOverCapacity() {
        if (byAddress.size() <= MAX_TRACKED_ADDRESSES) {
            return;
        }
        byAddress.values().removeIf(this::expired);

        int excess = byAddress.size() - MAX_TRACKED_ADDRESSES;
        if (excess <= 0) {
            return;
        }
        byAddress.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().lastSeen()))
                .limit(excess)
                .map(Map.Entry::getKey)
                .forEach(byAddress::remove);
    }

    /** Visible for testing. */
    int trackedAddresses() {
        return byAddress.size();
    }
}
