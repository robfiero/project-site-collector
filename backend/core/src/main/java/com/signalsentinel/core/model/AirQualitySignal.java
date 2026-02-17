package com.signalsentinel.core.model;

import java.time.Instant;

public record AirQualitySignal(
        String location,
        int aqi,
        String category,
        Instant updatedAt
) {
}
