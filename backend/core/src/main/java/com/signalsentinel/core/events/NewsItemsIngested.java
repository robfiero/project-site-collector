package com.signalsentinel.core.events;

import java.time.Instant;

public record NewsItemsIngested(
        Instant timestamp,
        String sourceId,
        int count
) implements Event {
    @Override
    public String type() {
        return "NewsItemsIngested";
    }
}
