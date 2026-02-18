package com.signalsentinel.service.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.signalsentinel.core.util.JsonUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class TigerwebZipResolver implements ZipGeoResolver {
    private static final String TIGER_BASE = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_ACS2022/MapServer/0/query";

    private final HttpClient httpClient;
    private final Duration timeout;
    private final Clock clock;

    public TigerwebZipResolver(HttpClient httpClient, Duration timeout, Clock clock) {
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public ZipGeoRecord resolve(String zip) {
        String normalized = normalizeZip(zip);
        String where = "ZCTA5='" + normalized + "'";
        String query = "where=" + URLEncoder.encode(where, StandardCharsets.UTF_8)
                + "&outFields=" + URLEncoder.encode("ZCTA5,INTPTLAT,INTPTLON,CENTLAT,CENTLON", StandardCharsets.UTF_8)
                + "&f=pjson";
        URI uri = URI.create(TIGER_BASE + "?" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("TIGERweb zip lookup failed with status " + response.statusCode());
            }
            JsonNode root = JsonUtils.objectMapper().readTree(response.body());
            JsonNode features = root.path("features");
            if (!features.isArray() || features.isEmpty()) {
                throw new IllegalArgumentException("ZIP not found in TIGERweb: " + normalized);
            }
            JsonNode attributes = features.get(0).path("attributes");
            Double lat = parseCoordinate(attributes, "INTPTLAT");
            Double lon = parseCoordinate(attributes, "INTPTLON");
            if (lat == null || lon == null) {
                lat = parseCoordinate(attributes, "CENTLAT");
                lon = parseCoordinate(attributes, "CENTLON");
            }
            if (lat == null || lon == null) {
                throw new IllegalStateException("TIGERweb response missing coordinates for ZIP " + normalized);
            }
            return new ZipGeoRecord(normalized, lat, lon, Instant.now(clock), "tigerweb_zcta");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve ZIP via TIGERweb: " + normalized, e);
        }
    }

    private static String normalizeZip(String zip) {
        String normalized = zip == null ? "" : zip.trim();
        if (!normalized.matches("\\d{5}")) {
            throw new IllegalArgumentException("ZIP must be exactly 5 digits");
        }
        return normalized;
    }

    private static Double parseCoordinate(JsonNode attributes, String field) {
        JsonNode value = attributes.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            String text = value.asText().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

