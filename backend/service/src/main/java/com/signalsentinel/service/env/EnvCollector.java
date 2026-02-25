package com.signalsentinel.service.env;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.EnvAqiUpdated;
import com.signalsentinel.core.events.EnvWeatherUpdated;
import com.signalsentinel.core.model.WeatherSignal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class EnvCollector implements Collector {
    private static final Logger LOGGER = Logger.getLogger(EnvCollector.class.getName());

    private final EnvService envService;
    private final Supplier<List<String>> zipSupplier;
    private final Duration interval;
    private final java.util.Set<String> weatherEmittedZips = ConcurrentHashMap.newKeySet();

    public EnvCollector(EnvService envService, Supplier<List<String>> zipSupplier, Duration interval) {
        this.envService = Objects.requireNonNull(envService, "envService is required");
        this.zipSupplier = Objects.requireNonNull(zipSupplier, "zipSupplier is required");
        this.interval = Objects.requireNonNull(interval, "interval is required");
    }

    @Override
    public String name() {
        return "envCollector";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public CompletableFuture<CollectorResult> poll(CollectorContext ctx) {
        Instant startedAt = ctx.clock().instant();
        ctx.eventBus().publish(new CollectorTickStarted(startedAt, name()));

        return CompletableFuture.supplyAsync(() -> runPoll(ctx))
                .handle((result, error) -> {
                    long durationMillis = Duration.between(startedAt, ctx.clock().instant()).toMillis();
                    if (error != null) {
                        String message = "Environment poll failed: " + rootMessage(error);
                        ctx.eventBus().publish(new AlertRaised(
                                ctx.clock().instant(),
                                "collector",
                                message,
                                Map.of("collector", name())
                        ));
                        ctx.eventBus().publish(new CollectorTickCompleted(ctx.clock().instant(), name(), false, durationMillis));
                        return CollectorResult.failure(message, Map.of("collector", name()));
                    }
                    ctx.eventBus().publish(new CollectorTickCompleted(
                            ctx.clock().instant(),
                            name(),
                            result.success(),
                            durationMillis
                    ));
                    return result;
                });
    }

    private CollectorResult runPoll(CollectorContext ctx) {
        Instant pollStartedAt = ctx.clock().instant();
        boolean emittedWeather = false;
        boolean emittedAqi = false;
        int alertsRaised = 0;
        List<String> targetZips = normalize(zipSupplier.get());
        boolean airNowKeyPresent = envService.isAirNowConfigured();
        boolean noaaUserAgentPresent = envService.isNoaaUserAgentPresent();
        boolean noaaFollowRedirects = envService.isNoaaFollowRedirects();
        LOGGER.info("ENV_START timestamp=" + pollStartedAt
                + " zips=" + targetZips
                + " airNowKeyPresent=" + airNowKeyPresent
                + " noaaUserAgentPresent=" + noaaUserAgentPresent
                + " noaaFollowRedirects=" + noaaFollowRedirects);
        if (targetZips.isEmpty()) {
            targetZips = envService.getStatuses(List.of(), airNowKeyPresent).stream().map(EnvStatus::zip).toList();
            if (targetZips.isEmpty()) {
                LOGGER.info("ENV_SKIP reason=missing_location");
                return CollectorResult.success("Environment polling skipped: no valid ZIP locations", Map.of(
                        "zips", List.of(),
                        "successes", 0,
                        "failures", 0
                ));
            }
        }
        if (!airNowKeyPresent) {
            LOGGER.info("ENV_SKIP_AIRNOW reason=missing_airnow_key");
        }

        int successCount = 0;
        int failureCount = 0;

        try {
            for (String zip : targetZips) {
                long zipFetchStartedAt = System.nanoTime();
                LOGGER.info(() -> "ENV_FETCH provider=NOAA url=https://api.weather.gov/points/{lat},{lon} zip=" + zip);
                if (airNowKeyPresent) {
                    LOGGER.info(() -> "ENV_FETCH provider=AIRNOW url=" + sanitizeSensitiveUrl("https://www.airnowapi.org/aq/observation/zipCode/current/?zipCode=" + zip) + " zip=" + zip);
                }
                try {
                    List<EnvStatus> statuses = envService.getStatuses(List.of(zip), airNowKeyPresent);
                    if (statuses.isEmpty()) {
                        LOGGER.info(() -> "ENV_SKIP reason=missing_location zip=" + zip);
                        continue;
                    }
                    EnvStatus status = statuses.getFirst();
                    LOGGER.info(() -> "ENV_LOCATION zip=" + zip + " lat=" + status.lat() + " lon=" + status.lon());
                    long durationMillis = nanosToMillis(System.nanoTime() - zipFetchStartedAt);
                    boolean[] emitted = publishStatus(ctx, status, airNowKeyPresent);
                    emittedWeather = emittedWeather || emitted[0];
                    emittedAqi = emittedAqi || emitted[1];
                    successCount++;

                    String noaaUrl = status.weather().requestUrl() == null ? "n/a" : status.weather().requestUrl();
                    LOGGER.info(() -> "ENV_FETCH_OK provider=NOAA status=200 durationMs=" + durationMillis + " url=" + noaaUrl);
                    if (airNowKeyPresent && status.aqi().requestUrl() != null) {
                        String aqiUrl = sanitizeSensitiveUrl(status.aqi().requestUrl());
                        LOGGER.info(() -> "ENV_FETCH_OK provider=AIRNOW status=200 durationMs=" + durationMillis + " url=" + aqiUrl);
                    } else if (!airNowKeyPresent) {
                        LOGGER.info(() -> "ENV_SKIP_AIRNOW reason=missing_airnow_key zip=" + zip);
                    }
                } catch (RuntimeException e) {
                    failureCount++;
                    String error = rootMessage(e);
                    String provider = inferProvider(error);
                    long durationMillis = nanosToMillis(System.nanoTime() - zipFetchStartedAt);
                    LOGGER.warning(() -> "ENV_FETCH_FAIL provider=" + provider
                            + " status=" + inferStatus(error)
                            + " durationMs=" + durationMillis
                            + " bodySnippet=" + snippet(error, 200));
                    boolean[] emitted = publishUnavailableEvents(ctx, zip, error, airNowKeyPresent);
                    emittedWeather = emittedWeather || emitted[0];
                    emittedAqi = emittedAqi || emitted[1];
                    alertsRaised++;
                    ctx.eventBus().publish(new AlertRaised(
                            ctx.clock().instant(),
                            "collector",
                            "Environment fetch failed for ZIP " + zip + ": " + error,
                            Map.of("collector", name(), "zip", zip)
                    ));
                }
            }
        } finally {
            long durationMillis = Duration.between(pollStartedAt, ctx.clock().instant()).toMillis();
            LOGGER.info("ENV_END durationMs=" + durationMillis
                    + " emittedWeather=" + emittedWeather
                    + " emittedAqi=" + emittedAqi
                    + " alertsRaised=" + alertsRaised);
        }

        Map<String, Object> stats = Map.of(
                "zips", targetZips,
                "successes", successCount,
                "failures", failureCount
        );
        if (failureCount == 0) {
            return CollectorResult.success("Environment polling completed", stats);
        }
        return CollectorResult.failure("Environment polling had failures", stats);
    }

    private boolean[] publishStatus(CollectorContext ctx, EnvStatus status, boolean includeAqi) {
        String zip = status.zip();
        String label = status.locationLabel() == null || status.locationLabel().isBlank()
                ? "ZIP " + zip
                : status.locationLabel();
        long fetchedAtEpochMillis = status.updatedAt().toEpochMilli();
        String weatherSource = normalizeSource(status.weather().source(), "NOAA");
        String aqiSource = normalizeSource(status.aqi().source(), "AIRNOW");
        boolean emittedWeather = false;
        boolean emittedAqi = false;
        if (status.weather().temperatureF() != null) {
            ctx.signalStore().putWeather(new WeatherSignal(
                    zip,
                    status.weather().temperatureF(),
                    status.weather().forecast(),
                    List.of(),
                    status.updatedAt()
            ));
            ctx.eventBus().publish(new EnvWeatherUpdated(
                    status.updatedAt(),
                    zip,
                    label,
                    status.lat(),
                    status.lon(),
                    status.weather().temperatureF(),
                    status.weather().forecast(),
                    weatherSource,
                    fetchedAtEpochMillis,
                    "OK",
                    null,
                    status.weather().requestUrl(),
                    status.weather().observationTime()
            ));
            emittedWeather = true;
        } else {
            String weatherUnavailableReason = status.weather().forecast() == null || status.weather().forecast().isBlank()
                    ? "Weather unavailable"
                    : status.weather().forecast();
            ctx.eventBus().publish(new EnvWeatherUpdated(
                    status.updatedAt(),
                    zip,
                    label,
                    status.lat(),
                    status.lon(),
                    Double.NaN,
                    weatherUnavailableReason,
                    weatherSource,
                    fetchedAtEpochMillis,
                    "UNAVAILABLE",
                    weatherUnavailableReason,
                    status.weather().requestUrl(),
                    status.weather().observationTime()
            ));
            emittedWeather = true;
        }
        if (weatherEmittedZips.add(zip)) {
            LOGGER.info(() -> "ENV_FIRST_EMIT provider=NOAA zip=" + zip + " reason=no_previous_cached_value");
        }

        if (includeAqi) {
            String aqiStatus = status.aqi().aqi() == null ? "UNAVAILABLE" : "OK";
            String aqiError = status.aqi().aqi() == null ? (status.aqi().message() == null ? "AQI unavailable" : status.aqi().message()) : null;
            ctx.eventBus().publish(new EnvAqiUpdated(
                    status.updatedAt(),
                    zip,
                    label,
                    status.lat(),
                    status.lon(),
                    status.aqi().aqi(),
                    status.aqi().category(),
                    status.aqi().message(),
                    aqiSource,
                    fetchedAtEpochMillis,
                    aqiStatus,
                    aqiError,
                    sanitizeSensitiveUrl(status.aqi().requestUrl()),
                    status.aqi().validDateTime()
            ));
            emittedAqi = true;
        }
        return new boolean[]{emittedWeather, emittedAqi};
    }

    private boolean[] publishUnavailableEvents(CollectorContext ctx, String zip, String error, boolean includeAqi) {
        Instant now = ctx.clock().instant();
        String label = "ZIP " + zip;
        long fetchedAtEpochMillis = now.toEpochMilli();
        boolean emittedWeather = false;
        boolean emittedAqi = false;
        ctx.eventBus().publish(new EnvWeatherUpdated(
                now,
                zip,
                label,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                "Unavailable",
                "NOAA",
                fetchedAtEpochMillis,
                "UNAVAILABLE",
                error,
                null,
                null
        ));
        emittedWeather = true;
        if (includeAqi) {
            ctx.eventBus().publish(new EnvAqiUpdated(
                    now,
                    zip,
                    label,
                    Double.NaN,
                    Double.NaN,
                    null,
                    null,
                    "AQI unavailable",
                    "AIRNOW",
                    fetchedAtEpochMillis,
                    "UNAVAILABLE",
                    error,
                    null,
                    null
            ));
            emittedAqi = true;
        }
        return new boolean[]{emittedWeather, emittedAqi};
    }

    private static List<String> normalize(List<String> zips) {
        if (zips == null || zips.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String zip : zips) {
            if (zip == null) {
                continue;
            }
            String value = zip.trim();
            if (value.matches("\\d{5}")) {
                normalized.add(value);
            }
        }
        return new ArrayList<>(normalized);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static String normalizeSource(String source, String fallback) {
        if (source == null || source.isBlank()) {
            return fallback;
        }
        return source.toUpperCase();
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private static String inferProvider(String message) {
        if (message == null) {
            return "UNKNOWN";
        }
        String lowered = message.toLowerCase();
        if (lowered.contains("airnow")) {
            return "AIRNOW";
        }
        if (lowered.contains("noaa") || lowered.contains("weather.gov")) {
            return "NOAA";
        }
        return "UNKNOWN";
    }

    private static String inferStatus(String message) {
        if (message == null) {
            return "unknown";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\bstatus\\s+(\\d{3})\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }

    private static String snippet(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= maxChars) {
            return singleLine;
        }
        return singleLine.substring(0, Math.max(0, maxChars));
    }

    private static String sanitizeSensitiveUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        return url.replaceAll("(?i)(API_KEY=)[^&]+", "$1***");
    }
}
