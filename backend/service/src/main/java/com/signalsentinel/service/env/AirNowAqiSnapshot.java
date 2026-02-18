package com.signalsentinel.service.env;

import java.time.Instant;

public record AirNowAqiSnapshot(
        Integer aqi,
        String category,
        Instant observedAt,
        String requestUrl,
        String validDateTime
) {
}
