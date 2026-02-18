package com.signalsentinel.core.events;

import java.time.Instant;

public record EnvWeatherUpdated(
        Instant timestamp,
        String zip,
        String locationLabel,
        double lat,
        double lon,
        double tempF,
        String conditions,
        String source,
        long fetchedAtEpochMillis,
        String status,
        String error,
        String requestUrl,
        String observationTime
) implements Event {
    @Override
    public String type() {
        return "EnvWeatherUpdated";
    }
}
