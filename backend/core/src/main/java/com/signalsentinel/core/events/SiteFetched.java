package com.signalsentinel.core.events;

import java.time.Instant;

public record SiteFetched(
        Instant timestamp,
        String siteId,
        String url,
        int status,
        long durationMillis
) implements Event {
    @Override
    public String type() {
        return "SiteFetched";
    }
}
