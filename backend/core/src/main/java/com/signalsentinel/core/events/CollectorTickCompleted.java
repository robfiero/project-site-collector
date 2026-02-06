package com.signalsentinel.core.events;

import java.time.Instant;

public record CollectorTickCompleted(
        Instant timestamp,
        String collectorName,
        boolean success,
        long durationMillis
) implements Event {
    @Override
    public String type() {
        return "CollectorTickCompleted";
    }
}
