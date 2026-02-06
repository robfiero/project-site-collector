package com.signalsentinel.core.model;

import java.time.Instant;
import java.util.List;

public record WeatherSignal(
        String location,
        double tempF,
        String conditions,
        List<String> alerts,
        Instant updatedAt
) {
}
