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
import java.util.Locale;
import java.util.logging.Logger;

public final class NoaaClient {
    private static final Logger LOGGER = Logger.getLogger(NoaaClient.class.getName());
    private static final String DEFAULT_USER_AGENT = "todays-overview/0.1 (contact: support@example.com)";
    private final HttpClient httpClient;
    private final Duration timeout;
    private final Clock clock;
    private final String userAgent;

    public NoaaClient(HttpClient httpClient, Duration timeout, Clock clock, String userAgent) {
        this.httpClient = httpClient;
        this.timeout = timeout;
        this.clock = clock;
        this.userAgent = normalizeUserAgent(userAgent);
    }

    public boolean hasUserAgentConfigured() {
        return userAgent != null && !userAgent.isBlank();
    }

    public boolean followsRedirects() {
        return true;
    }

    public NoaaWeatherSnapshot currentFor(double lat, double lon) {
        try {
            JsonNode points = getJson(URI.create("https://api.weather.gov/points/" + lat + "," + lon));
            JsonNode properties = points.path("properties");
            String forecastHourlyUrl = properties.path("forecastHourly").asText("");
            String forecastDailyUrl = properties.path("forecast").asText("");
            String forecastUrl = !forecastHourlyUrl.isBlank() ? forecastHourlyUrl : forecastDailyUrl;
            if (forecastUrl.isBlank()) {
                throw new IllegalStateException("NOAA points response missing forecast URLs");
            }
            JsonNode relativeProperties = properties
                    .path("relativeLocation")
                    .path("properties");
            String city = relativeProperties.path("city").asText("");
            String state = relativeProperties.path("state").asText("");
            JsonNode forecast = getJson(URI.create(forecastUrl));
            JsonNode periods = forecast.path("properties").path("periods");
            if (!periods.isArray() || periods.isEmpty()) {
                throw new IllegalStateException("NOAA forecast response missing periods");
            }
            JsonNode period = periods.get(0);
            Double temperature = period.path("temperature").isNumber() ? period.path("temperature").asDouble() : null;
            String summary = firstNonBlank(
                    period.path("shortForecast").asText(""),
                    period.path("detailedForecast").asText("")
            );
            String wind = period.path("windSpeed").asText("");
            String startTime = period.path("startTime").asText("");
            Instant observed = startTime.isBlank() ? Instant.now(clock) : Instant.parse(startTime);
            return new NoaaWeatherSnapshot(
                    temperature,
                    summary,
                    wind,
                    observed,
                    forecastUrl,
                    startTime.isBlank() ? null : startTime,
                    city.isBlank() ? null : city,
                    state.isBlank() ? null : state
            );
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("NOAA weather request failed", e);
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return "";
    }

    private JsonNode getJson(URI uri) throws Exception {
        int attempts = 0;
        while (true) {
            attempts++;
            HttpRequest request = requestFor(uri);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2) {
                return JsonUtils.objectMapper().readTree(response.body());
            }
            if (isRedirect(status)) {
                String location = response.headers().firstValue("Location")
                        .orElse(response.headers().firstValue("location").orElse(""));
                if (!location.isBlank()) {
                    URI redirectUri = uri.resolve(location);
                    LOGGER.info(() -> "NOAA redirect: " + uri + " -> " + redirectUri);
                    HttpResponse<String> redirected = httpClient.send(requestFor(redirectUri), HttpResponse.BodyHandlers.ofString());
                    if (redirected.statusCode() / 100 == 2) {
                        return JsonUtils.objectMapper().readTree(redirected.body());
                    }
                    throw new IllegalStateException("NOAA redirect request failed with status "
                            + redirected.statusCode() + " for " + redirectUri);
                }
            }
            if (attempts >= 2 || status < 500) {
                throw new IllegalStateException("NOAA request failed with status " + status + " for " + uri);
            }
        }
    }

    private HttpRequest requestFor(URI uri) {
        return HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(timeout)
                    .header("Accept", "application/geo+json")
                    .header("User-Agent", userAgent)
                    .build();
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 307 || status == 308;
    }

    private static String normalizeUserAgent(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_USER_AGENT;
        }
        String value = configured.trim();
        if (!value.toLowerCase(Locale.ROOT).contains("contact:")) {
            return value + " (contact: support@example.com)";
        }
        return value;
    }
}
