package com.signalsentinel.core.events;

import java.time.Instant;
import java.util.Map;

public record AlertRaised(
        Instant timestamp,
        String category,
        String message,
        Map<String, Object> details
) implements Event {
    @Override
    public String type() {
        return "AlertRaised";
    }
}
