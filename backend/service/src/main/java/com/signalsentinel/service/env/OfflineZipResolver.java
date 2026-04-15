package com.signalsentinel.service.env;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.signalsentinel.core.util.JsonUtils;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Resolves ZIP codes to geo records using a bundled offline dataset derived from
 * GeoNames US postal codes (public domain). Covers ~41,000 US ZIP codes with city,
 * state abbreviation, and centroid lat/lon — no network call required.
 *
 * Falls back to a provided {@link ZipGeoResolver} for any ZIP not in the dataset.
 */
public final class OfflineZipResolver implements ZipGeoResolver {
    private static final Logger LOGGER = Logger.getLogger(OfflineZipResolver.class.getName());
    private static final String RESOURCE_PATH = "/data/zip-data.json";

    private final Map<String, JsonNode> zipData;
    private final ZipGeoResolver fallback;
    private final Clock clock;

    public OfflineZipResolver(ZipGeoResolver fallback, Clock clock) {
        this.fallback = fallback;
        this.clock = clock;
        this.zipData = loadZipData();
    }

    @Override
    public ZipGeoRecord resolve(String zip) {
        String normalized = zip == null ? "" : zip.trim();
        if (!normalized.matches("\\d{5}")) {
            throw new IllegalArgumentException("ZIP must be exactly 5 digits: " + zip);
        }

        JsonNode entry = zipData.get(normalized);
        if (entry != null) {
            String city  = entry.path("city").asText(null);
            String state = entry.path("state").asText(null);
            double lat   = entry.path("lat").asDouble();
            double lon   = entry.path("lon").asDouble();
            return new ZipGeoRecord(normalized, lat, lon, Instant.now(clock), "offline_geonames", city, state);
        }

        LOGGER.warning("ZIP not found in offline dataset, falling back to TIGERweb: " + normalized);
        ZipGeoRecord fallbackRecord = fallback.resolve(normalized);
        // Promote to full record with null city/state (TIGERweb doesn't provide them)
        return new ZipGeoRecord(
                fallbackRecord.zip(),
                fallbackRecord.lat(),
                fallbackRecord.lon(),
                fallbackRecord.resolvedAt(),
                fallbackRecord.source(),
                null,
                null
        );
    }

    private static Map<String, JsonNode> loadZipData() {
        try (InputStream in = OfflineZipResolver.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                LOGGER.warning("Offline ZIP dataset not found at " + RESOURCE_PATH + "; all lookups will use TIGERweb");
                return Collections.emptyMap();
            }
            return JsonUtils.objectMapper().readValue(in, new TypeReference<>() {});
        } catch (Exception e) {
            LOGGER.warning("Failed to load offline ZIP dataset: " + e.getMessage() + "; all lookups will use TIGERweb");
            return Collections.emptyMap();
        }
    }
}
