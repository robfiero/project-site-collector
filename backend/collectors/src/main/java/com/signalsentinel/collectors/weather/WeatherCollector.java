package com.signalsentinel.collectors.weather;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.collectors.config.WeatherCollectorConfig;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.WeatherUpdated;
import com.signalsentinel.core.model.WeatherSignal;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class WeatherCollector implements Collector {
    public static final String CONFIG_KEY = "weatherCollector";

    private final WeatherProvider weatherProvider;
    private final Duration interval;

    public WeatherCollector(WeatherProvider weatherProvider) {
        this(weatherProvider, Duration.ofSeconds(60));
    }

    public WeatherCollector(WeatherProvider weatherProvider, Duration interval) {
        this.weatherProvider = weatherProvider;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "weatherCollector";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public CompletableFuture<CollectorResult> poll(CollectorContext ctx) {
        Instant tickStartedAt = ctx.clock().instant();
        ctx.eventBus().publish(new CollectorTickStarted(tickStartedAt, name()));

        WeatherCollectorConfig cfg = ctx.requiredConfig(CONFIG_KEY, WeatherCollectorConfig.class);
        List<CompletableFuture<WeatherPollOutcome>> tasks = cfg.locations().stream()
                .map(location -> CompletableFuture.supplyAsync(() -> pollLocation(location, ctx))
                        .orTimeout(ctx.requestTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .exceptionally(error -> failedOutcome(location, ctx, error)))
                .toList();

        CompletableFuture<CollectorResult> pipeline = CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> summarize(tasks.stream().map(CompletableFuture::join).toList()));

        return pipeline.handle((result, error) -> {
            long durationMillis = Duration.between(tickStartedAt, ctx.clock().instant()).toMillis();
            if (error != null) {
                ctx.eventBus().publish(new CollectorTickCompleted(ctx.clock().instant(), name(), false, durationMillis));
                return CollectorResult.failure("Weather collector failed: " + rootMessage(error), Map.of());
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

    private WeatherPollOutcome pollLocation(String location, CollectorContext ctx) {
        WeatherReading reading = weatherProvider.getReading(location);
        WeatherSignal signal = new WeatherSignal(
                location,
                reading.tempF(),
                reading.conditions(),
                reading.alerts(),
                ctx.clock().instant()
        );
        ctx.signalStore().putWeather(signal);
        ctx.eventBus().publish(new WeatherUpdated(
                ctx.clock().instant(),
                location,
                reading.tempF(),
                reading.conditions()
        ));

        List<String> alerts = reading.alerts() == null ? List.of() : reading.alerts();
        alerts.forEach(alert -> ctx.eventBus().publish(new AlertRaised(
                ctx.clock().instant(),
                "weather_alert",
                alert,
                Map.of("location", location)
        )));

        return new WeatherPollOutcome(location, true, alerts.size());
    }

    private WeatherPollOutcome failedOutcome(String location, CollectorContext ctx, Throwable error) {
        ctx.eventBus().publish(new AlertRaised(
                ctx.clock().instant(),
                "collector",
                "Weather fetch failed for " + location + ": " + rootMessage(error),
                Map.of("collector", name(), "location", location)
        ));
        return new WeatherPollOutcome(location, false, 0);
    }

    private CollectorResult summarize(List<WeatherPollOutcome> outcomes) {
        long successes = outcomes.stream().filter(WeatherPollOutcome::success).count();
        int alertCount = outcomes.stream().mapToInt(WeatherPollOutcome::alertCount).sum();
        Map<String, Long> locations = outcomes.stream()
                .collect(Collectors.groupingBy(WeatherPollOutcome::location, Collectors.counting()));

        Map<String, Object> stats = new HashMap<>();
        stats.put("locations", locations.keySet());
        stats.put("successes", successes);
        stats.put("alerts", alertCount);

        if (successes == outcomes.size()) {
            return CollectorResult.success("Weather polling completed", stats);
        }
        return CollectorResult.failure("Weather polling had failures", stats);
    }

    private record WeatherPollOutcome(String location, boolean success, int alertCount) {
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
