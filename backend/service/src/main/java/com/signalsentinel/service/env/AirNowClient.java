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
import java.util.Optional;

public final class AirNowClient {
    private final HttpClient httpClient;
    private final Duration timeout;
    private final Clock clock;
    private final String apiKey;

    public AirNowClient(HttpClient httpClient, Duration timeout, Clock clock, String apiKey) {
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.clock = clock;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public Optional<AirNowAqiSnapshot> currentForZip(String zip) {
        if (apiKey.isBlank()) {
            return Optional.empty();
        }
        String normalized = zip == null ? "" : zip.trim();
        if (!normalized.matches("\\d{5}")) {
            throw new IllegalArgumentException("ZIP must be exactly 5 digits");
        }

        String query = "format=application/json"
                + "&zipCode=" + URLEncoder.encode(normalized, StandardCharsets.UTF_8)
                + "&distance=25"
                + "&API_KEY=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        URI uri = URI.create("https://www.airnowapi.org/aq/observation/zipCode/current/?" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(timeout)
                .header("Accept", "application/json")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("AirNow request failed with status " + response.statusCode());
            }
            JsonNode root = JsonUtils.objectMapper().readTree(response.body());
            if (!root.isArray() || root.isEmpty()) {
                return Optional.empty();
            }

            Integer bestAqi = null;
            String bestCategory = null;
            String bestValidDateTime = null;
            for (JsonNode row : root) {
                JsonNode aqiNode = row.path("AQI");
                if (!aqiNode.isNumber()) {
                    continue;
                }
                int value = aqiNode.asInt();
                if (bestAqi == null || value > bestAqi) {
                    bestAqi = value;
                    bestCategory = row.path("Category").path("Name").asText("");
                    String dateObserved = row.path("DateObserved").asText("");
                    String hourObserved = row.path("HourObserved").asText("");
                    if (!dateObserved.isBlank() && !hourObserved.isBlank()) {
                        bestValidDateTime = dateObserved + " " + hourObserved + ":00";
                    } else {
                        bestValidDateTime = null;
                    }
                }
            }
            if (bestAqi == null) {
                return Optional.empty();
            }
            return Optional.of(new AirNowAqiSnapshot(
                    bestAqi,
                    bestCategory,
                    Instant.now(clock),
                    uri.toString(),
                    bestValidDateTime
            ));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AirNow request failed", e);
        }
    }
}
