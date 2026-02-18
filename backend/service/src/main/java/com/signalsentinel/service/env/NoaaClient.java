package com.signalsentinel.service.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.signalsentinel.core.util.JsonUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class NoaaClient {
    private final HttpClient httpClient;
    private final Duration timeout;
    private final Clock clock;
    private final String userAgent;

    public NoaaClient(HttpClient httpClient, Duration timeout, Clock clock, String userAgent) {
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.clock = clock;
        this.userAgent = userAgent;
    }

    public NoaaWeatherSnapshot currentFor(double lat, double lon) {
        try {
            JsonNode points = getJson(URI.create("https://api.weather.gov/points/" + lat + "," + lon));
            String forecastUrl = points.path("properties").path("forecast").asText("");
            if (forecastUrl.isBlank()) {
                throw new IllegalStateException("NOAA points response missing forecast URL");
            }
            JsonNode forecast = getJson(URI.create(forecastUrl));
            JsonNode periods = forecast.path("properties").path("periods");
            if (!periods.isArray() || periods.isEmpty()) {
                throw new IllegalStateException("NOAA forecast response missing periods");
            }
            JsonNode period = periods.get(0);
            Double temperature = period.path("temperature").isNumber() ? period.path("temperature").asDouble() : null;
            String summary = period.path("shortForecast").asText("");
            String wind = period.path("windSpeed").asText("");
            String startTime = period.path("startTime").asText("");
            Instant observed = startTime.isBlank() ? Instant.now(clock) : Instant.parse(startTime);
            return new NoaaWeatherSnapshot(
                    temperature,
                    summary,
                    wind,
                    observed,
                    forecastUrl,
                    startTime.isBlank() ? null : startTime
            );
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("NOAA weather request failed", e);
        }
    }

    private JsonNode getJson(URI uri) throws Exception {
        int attempts = 0;
        while (true) {
            attempts++;
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(timeout)
                    .header("Accept", "application/geo+json,application/json")
                    .header("User-Agent", userAgent)
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return JsonUtils.objectMapper().readTree(response.body());
            }
            if (attempts >= 2 || response.statusCode() < 500) {
                throw new IllegalStateException("NOAA request failed with status " + response.statusCode() + " for " + uri);
            }
        }
    }
}
