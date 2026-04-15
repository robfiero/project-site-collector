package com.signalsentinel.service.env;

import java.time.Instant;

public record ZipGeoRecord(
        String zip,
        double lat,
        double lon,
        Instant resolvedAt,
        String source,
        String city,
        String state
) {
    /** Backward-compatible constructor for records that pre-date the city/state fields. */
    public ZipGeoRecord(String zip, double lat, double lon, Instant resolvedAt, String source) {
        this(zip, lat, lon, resolvedAt, source, null, null);
    }
}

