package com.signalsentinel.core.events;

import java.time.Instant;

public record CollectorTickStarted(Instant timestamp, String collectorName) implements Event {
    @Override
    public String type() {
        return "CollectorTickStarted";
    }
}
