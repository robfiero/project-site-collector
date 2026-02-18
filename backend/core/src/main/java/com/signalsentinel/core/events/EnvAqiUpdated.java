package com.signalsentinel.core.events;

import java.time.Instant;

public record EnvAqiUpdated(
        Instant timestamp,
        String zip,
        String locationLabel,
        double lat,
        double lon,
        Integer aqi,
        String category,
        String message,
        String source,
        long fetchedAtEpochMillis,
        String status,
        String error,
        String requestUrl,
        String validDateTime
) implements Event {
    @Override
    public String type() {
        return "EnvAqiUpdated";
    }
}
