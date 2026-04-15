package com.signalsentinel.service.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleRateLimiter {
    private static final int DEFAULT_MAX_KEYS = 10_000;

    private final Clock clock;
    private final int limit;
    private final long windowSeconds;
    private final int maxKeys;
    private final Map<String, ArrayDeque<Instant>> buckets = new ConcurrentHashMap<>();

    public SimpleRateLimiter(Clock clock, int limit, long windowSeconds) {
        this(clock, limit, windowSeconds, DEFAULT_MAX_KEYS);
    }

    SimpleRateLimiter(Clock clock, int limit, long windowSeconds, int maxKeys) {
        this.clock = clock;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
        this.maxKeys = maxKeys;
    }

    public boolean tryAcquire(String key) {
        boolean[] result = {false};
        buckets.compute(key, (k, existing) -> {
            Instant now = clock.instant();
            Instant threshold = now.minusSeconds(windowSeconds);

            if (existing == null) {
                // Reject new keys when the table is full to prevent unbounded growth.
                if (buckets.size() >= maxKeys) {
                    return null;
                }
                existing = new ArrayDeque<>();
            }

            while (!existing.isEmpty() && existing.peekFirst().isBefore(threshold)) {
                existing.removeFirst();
            }

            if (existing.size() < limit) {
                existing.addLast(now);
                result[0] = true;
            }

            // Return null to remove the entry when the deque is empty (all timestamps expired
            // and no new entry added), preventing stale buckets from lingering in the table.
            return existing.isEmpty() ? null : existing;
        });
        return result[0];
    }
}
