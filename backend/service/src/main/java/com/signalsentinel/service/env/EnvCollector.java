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
import java.util.function.Supplier;

public final class EnvCollector implements Collector {
    private static final Map<String, String> ZIP_LABELS = Map.of(
            "02108", "Boston, MA (02108)",
            "98101", "Seattle, WA (98101)"
    );

    private final EnvService envService;
    private final Supplier<List<String>> zipSupplier;
    private final Duration interval;

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
        List<String> targetZips = normalize(zipSupplier.get());
        if (targetZips.isEmpty()) {
            targetZips = envService.getStatuses(List.of()).stream().map(EnvStatus::zip).toList();
        }

        int successCount = 0;
        int failureCount = 0;

        for (String zip : targetZips) {
            try {
                List<EnvStatus> statuses = envService.getStatuses(List.of(zip));
                if (statuses.isEmpty()) {
                    continue;
                }
                EnvStatus status = statuses.getFirst();
                publishStatus(ctx, status);
                successCount++;
            } catch (RuntimeException e) {
                failureCount++;
                publishUnavailableEvents(ctx, zip, rootMessage(e));
                ctx.eventBus().publish(new AlertRaised(
                        ctx.clock().instant(),
                        "collector",
                        "Environment fetch failed for ZIP " + zip + ": " + rootMessage(e),
                        Map.of("collector", name(), "zip", zip)
                ));
            }
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

    private void publishStatus(CollectorContext ctx, EnvStatus status) {
        String zip = status.zip();
        String label = locationLabel(zip);
        long fetchedAtEpochMillis = status.updatedAt().toEpochMilli();
        String weatherSource = normalizeSource(status.weather().source(), "NOAA");
        String aqiSource = normalizeSource(status.aqi().source(), "AIRNOW");
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
        } else {
            ctx.eventBus().publish(new EnvWeatherUpdated(
                    status.updatedAt(),
                    zip,
                    label,
                    status.lat(),
                    status.lon(),
                    Double.NaN,
                    status.weather().forecast(),
                    weatherSource,
                    fetchedAtEpochMillis,
                    "UNAVAILABLE",
                    "Weather unavailable",
                    status.weather().requestUrl(),
                    status.weather().observationTime()
            ));
        }

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
                status.aqi().requestUrl(),
                status.aqi().validDateTime()
        ));
    }

    private void publishUnavailableEvents(CollectorContext ctx, String zip, String error) {
        Instant now = ctx.clock().instant();
        String label = locationLabel(zip);
        long fetchedAtEpochMillis = now.toEpochMilli();
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

    private static String locationLabel(String zip) {
        return ZIP_LABELS.getOrDefault(zip, "ZIP " + zip);
    }
}
