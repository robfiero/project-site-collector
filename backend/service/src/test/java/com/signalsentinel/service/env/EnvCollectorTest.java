package com.signalsentinel.service.env;

import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.EnvAqiUpdated;
import com.signalsentinel.core.events.EnvWeatherUpdated;
import com.signalsentinel.service.store.JsonFileSignalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvCollectorTest {
    @TempDir
    Path tempDir;

    @Test
    void emitsWeatherAndAqiEventsForResolvedZip() {
        EventBus eventBus = new EventBus();
        List<Object> events = new ArrayList<>();
        eventBus.subscribe(EnvWeatherUpdated.class, events::add);
        eventBus.subscribe(EnvAqiUpdated.class, events::add);

        Clock clock = Clock.fixed(Instant.parse("2026-02-18T12:00:00Z"), ZoneOffset.UTC);
        EnvService envService = new EnvService(
                new ZipGeoStore(tempDir.resolve("zip-geo.json")),
                zip -> new ZipGeoRecord(zip, 42.35, -71.06, Instant.now(clock), "test"),
                (lat, lon) -> new NoaaWeatherSnapshot(72.5, "Clear", "5 mph", Instant.now(clock), "https://api.weather.gov/mock", "2026-02-18T12:00:00Z", "Boston", "MA"),
                zip -> Optional.of(new AirNowAqiSnapshot(42, "Good", Instant.now(clock), "https://www.airnowapi.org/mock", "2026-02-18 12:00")),
                clock,
                List.of("02108")
        );
        EnvCollector collector = new EnvCollector(envService, () -> List.of("02108"), Duration.ofSeconds(30));
        CollectorContext ctx = new CollectorContext(
                HttpClient.newHttpClient(),
                eventBus,
                new JsonFileSignalStore(tempDir.resolve("signals.json")),
                clock,
                Duration.ofSeconds(1),
                java.util.Map.of()
        );

        CollectorResult result = collector.poll(ctx).join();

        assertTrue(result.success());
        assertEquals(2, events.size());
        assertTrue(events.stream().anyMatch(EnvWeatherUpdated.class::isInstance));
        assertTrue(events.stream().anyMatch(EnvAqiUpdated.class::isInstance));
    }

    @Test
    void continuesWhenOneZipFails() {
        EventBus eventBus = new EventBus();
        List<AlertRaised> alerts = new ArrayList<>();
        List<EnvWeatherUpdated> weatherEvents = new ArrayList<>();
        eventBus.subscribe(AlertRaised.class, alerts::add);
        eventBus.subscribe(EnvWeatherUpdated.class, weatherEvents::add);

        Clock clock = Clock.fixed(Instant.parse("2026-02-18T12:00:00Z"), ZoneOffset.UTC);
        EnvService envService = new EnvService(
                new ZipGeoStore(tempDir.resolve("zip-geo-2.json")),
                zip -> {
                    if (zip.equals("99999")) {
                        throw new IllegalArgumentException("ZIP not found");
                    }
                    return new ZipGeoRecord(zip, 47.61, -122.33, Instant.now(clock), "test");
                },
                (lat, lon) -> new NoaaWeatherSnapshot(58.0, "Cloudy", "8 mph", Instant.now(clock), "https://api.weather.gov/mock", "2026-02-18T12:00:00Z", "Seattle", "WA"),
                zip -> Optional.empty(),
                clock,
                List.of("98101")
        );
        EnvCollector collector = new EnvCollector(envService, () -> List.of("99999", "98101"), Duration.ofSeconds(30));
        CollectorContext ctx = new CollectorContext(
                HttpClient.newHttpClient(),
                eventBus,
                new JsonFileSignalStore(tempDir.resolve("signals-2.json")),
                clock,
                Duration.ofSeconds(1),
                java.util.Map.of()
        );

        CollectorResult result = collector.poll(ctx).join();

        assertTrue(result.success());
        assertTrue(alerts.isEmpty());
        assertTrue(weatherEvents.stream().anyMatch(event ->
                event.zip().equals("99999")
                        && event.status().equals("UNAVAILABLE")
                        && event.error() != null
                        && event.error().contains("ZIP")));
    }
}
