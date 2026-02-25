package com.signalsentinel.service.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleRateLimiter {
    private final Clock clock;
    private final int limit;
    private final long windowSeconds;
    private final Map<String, ArrayDeque<Instant>> buckets = new ConcurrentHashMap<>();

    public SimpleRateLimiter(Clock clock, int limit, long windowSeconds) {
        this.clock = clock;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public boolean tryAcquire(String key) {
        ArrayDeque<Instant> deque = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            Instant now = clock.instant();
            Instant threshold = now.minusSeconds(windowSeconds);
            while (!deque.isEmpty() && deque.peekFirst().isBefore(threshold)) {
                deque.removeFirst();
            }
            if (deque.size() >= limit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }
}
