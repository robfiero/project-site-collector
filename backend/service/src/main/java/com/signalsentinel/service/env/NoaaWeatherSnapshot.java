package com.signalsentinel.service.env;

import java.time.Instant;

public record NoaaWeatherSnapshot(
        Double temperatureF,
        String shortForecast,
        String windSpeed,
        Instant observedAt,
        String requestUrl,
        String observationTime
) {
}
