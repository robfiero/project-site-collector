package com.signalsentinel.core.events;

import java.time.Instant;

public record LocalHappeningsIngested(
        Instant timestamp,
        String source,
        String location,
        int itemCount
) implements Event {
    @Override
    public String type() {
        return "LocalHappeningsIngested";
    }
}
