package com.signalsentinel.service.env;

import java.time.Instant;

public record ZipGeoRecord(
        String zip,
        double lat,
        double lon,
        Instant resolvedAt,
        String source
) {
}

