package com.signalsentinel.service.env;

import java.time.Instant;

public record EnvStatus(
        String zip,
        double lat,
        double lon,
        Weather weather,
        AirQuality aqi,
        Instant updatedAt
) {
    public record Weather(
            Double temperatureF,
            String forecast,
            String windSpeed,
            Instant observedAt,
            String source,
            String requestUrl,
            String observationTime
    ) {
    }

    public record AirQuality(
            Integer aqi,
            String category,
            Instant observedAt,
            String message,
            String source,
            String requestUrl,
            String validDateTime
    ) {
    }
}
