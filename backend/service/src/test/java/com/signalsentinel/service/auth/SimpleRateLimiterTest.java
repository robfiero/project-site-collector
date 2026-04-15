package com.signalsentinel.service.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleRateLimiterTest {
    @Test
    void allowsUpToLimitThenBlocksWithinWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-25T10:00:00Z"));
        SimpleRateLimiter limiter = new SimpleRateLimiter(clock, 3, 60);

        assertTrue(limiter.tryAcquire("ip:1.1.1.1"));
        assertTrue(limiter.tryAcquire("ip:1.1.1.1"));
        assertTrue(limiter.tryAcquire("ip:1.1.1.1"));
        assertFalse(limiter.tryAcquire("ip:1.1.1.1"));
    }

    @Test
    void rejectsNewKeysWhenAtMaxCapacity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-25T10:00:00Z"));
        SimpleRateLimiter limiter = new SimpleRateLimiter(clock, 5, 60, 2);

        // Fill up to the cap with two distinct keys.
        assertTrue(limiter.tryAcquire("key:1"));
        assertTrue(limiter.tryAcquire("key:2"));

        // A third distinct key should be rejected because the cap is 2.
        assertFalse(limiter.tryAcquire("key:3"));

        // Existing keys still work normally.
        assertTrue(limiter.tryAcquire("key:1"));
        assertTrue(limiter.tryAcquire("key:2"));
    }

    @Test
    void permitsAgainAfterWindowExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-25T10:00:00Z"));
        SimpleRateLimiter limiter = new SimpleRateLimiter(clock, 2, 60);

        assertTrue(limiter.tryAcquire("email:user@example.com"));
        assertTrue(limiter.tryAcquire("email:user@example.com"));
        assertFalse(limiter.tryAcquire("email:user@example.com"));

        clock.set(Instant.parse("2026-02-25T10:01:01Z"));
        assertTrue(limiter.tryAcquire("email:user@example.com"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
