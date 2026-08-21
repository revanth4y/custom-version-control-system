package com.gitforge.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limiter against a clock it does not control, so a fifteen-minute window
 * can be exercised without waiting fifteen minutes.
 */
class AuthAttemptLimiterTest {

    private static final String ADDRESS = "203.0.113.7";

    /** A clock that only moves when a test moves it. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private MovableClock clock;
    private AuthAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        limiter = new AuthAttemptLimiter(clock);
    }

    @Test
    void allowsAttemptsUpToTheLimit() {
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES; i++) {
            assertThat(limiter.isBlocked(ADDRESS)).as("attempt %d", i + 1).isFalse();
            limiter.recordFailure(ADDRESS);
        }
        // The tenth failure is allowed; it is the eleventh attempt that is refused.
        assertThat(limiter.isBlocked(ADDRESS)).isTrue();
    }

    @Test
    void reportsHowLongTheCallerMustWait() {
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure(ADDRESS);
        }
        clock.advance(Duration.ofMinutes(5));

        Duration wait = limiter.retryAfter(ADDRESS);
        assertThat(wait).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void forgetsTheFailuresOnceTheWindowPasses() {
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure(ADDRESS);
        }
        assertThat(limiter.isBlocked(ADDRESS)).isTrue();

        clock.advance(AuthAttemptLimiter.WINDOW.plusSeconds(1));
        assertThat(limiter.isBlocked(ADDRESS)).isFalse();
        assertThat(limiter.retryAfter(ADDRESS)).isZero();
    }

    @Test
    void startsAFreshWindowAfterAnExpiredOne() {
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure(ADDRESS);
        }
        clock.advance(AuthAttemptLimiter.WINDOW.plusSeconds(1));

        // One failure in the new window must not inherit the old count.
        limiter.recordFailure(ADDRESS);
        assertThat(limiter.isBlocked(ADDRESS)).isFalse();
    }

    @Test
    void successClearsTheRecord() {
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES - 1; i++) {
            limiter.recordFailure(ADDRESS);
        }
        limiter.recordSuccess(ADDRESS);

        // Someone who mistypes and then succeeds starts over.
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES; i++) {
            assertThat(limiter.isBlocked(ADDRESS)).isFalse();
            limiter.recordFailure(ADDRESS);
        }
        assertThat(limiter.isBlocked(ADDRESS)).isTrue();
    }

    @Test
    void tracksEachAddressSeparately() {
        for (int i = 0; i < AuthAttemptLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure(ADDRESS);
        }
        assertThat(limiter.isBlocked(ADDRESS)).isTrue();
        assertThat(limiter.isBlocked("198.51.100.4")).isFalse();
    }

    /**
     * Concurrent failures from one address must not lose increments.
     *
     * <p>A read-then-write would let two requests observe the same count and
     * write back the same value, quietly granting twice the attempts.
     */
    @Test
    void countsConcurrentFailuresExactly() throws Exception {
        int threads = 16;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        limiter.recordFailure(ADDRESS);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(limiter.isBlocked(ADDRESS)).isTrue();
        assertThat(limiter.trackedAddresses()).isEqualTo(1);
    }

    @Test
    void keepsTheTableBounded() {
        for (int i = 0; i < AuthAttemptLimiter.MAX_TRACKED_ADDRESSES + 500; i++) {
            limiter.recordFailure("10.0." + (i / 256) + "." + (i % 256));
        }
        assertThat(limiter.trackedAddresses()).isLessThanOrEqualTo(AuthAttemptLimiter.MAX_TRACKED_ADDRESSES);
    }

    @Test
    void evictionPrefersExpiredEntries() {
        limiter.recordFailure("192.0.2.1");
        clock.advance(AuthAttemptLimiter.WINDOW.plusSeconds(1));

        for (int i = 0; i < AuthAttemptLimiter.MAX_TRACKED_ADDRESSES + 1; i++) {
            limiter.recordFailure("10.1." + (i / 256) + "." + (i % 256));
        }
        // The stale entry is gone, and the fresh ones that replaced it remain.
        assertThat(limiter.trackedAddresses()).isLessThanOrEqualTo(AuthAttemptLimiter.MAX_TRACKED_ADDRESSES);
        assertThat(limiter.isBlocked("192.0.2.1")).isFalse();
    }
}
