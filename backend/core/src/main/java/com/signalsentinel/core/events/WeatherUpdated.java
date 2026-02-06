package com.signalsentinel.core.events;

import java.time.Instant;

public record WeatherUpdated(
        Instant timestamp,
        String location,
        double tempF,
        String conditions
) implements Event {
    @Override
    public String type() {
        return "WeatherUpdated";
    }
}
