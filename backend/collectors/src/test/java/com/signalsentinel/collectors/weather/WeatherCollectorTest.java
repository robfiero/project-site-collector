package com.signalsentinel.collectors.weather;

import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.config.WeatherCollectorConfig;
import com.signalsentinel.collectors.support.EventCapture;
import com.signalsentinel.collectors.support.FixtureUtils;
import com.signalsentinel.collectors.support.InMemorySignalStore;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.WeatherUpdated;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherCollectorTest {
    @Test
    void loadsMockFixtureAndEmitsUpdatesAndAlerts() {
        MockWeatherProvider provider = new MockWeatherProvider(FixtureUtils.fixturePath("fixtures/mock-weather.json"));
        WeatherCollector collector = new WeatherCollector(provider);

        WeatherCollectorConfig cfg = new WeatherCollectorConfig(Duration.ofSeconds(60), List.of("Boston", "Seattle"));
        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();

        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                store,
                Clock.fixed(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC),
                Duration.ofMillis(200),
                Map.of(WeatherCollector.CONFIG_KEY, cfg)
        );

        collector.poll(ctx).join();

        assertTrue(store.getWeather("Boston").isPresent());
        assertTrue(store.getWeather("Seattle").isPresent());
        assertEquals(2, capture.byType(WeatherUpdated.class).size());
        assertEquals(1, capture.byType(AlertRaised.class).size());
    }

    @Test
    void missingLocationRaisesCollectorAlertAndContinues() {
        MockWeatherProvider provider = new MockWeatherProvider(FixtureUtils.fixturePath("fixtures/mock-weather.json"));
        WeatherCollector collector = new WeatherCollector(provider);

        WeatherCollectorConfig cfg = new WeatherCollectorConfig(Duration.ofSeconds(60), List.of("Boston", "Nowhere"));
        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();

        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                store,
                Clock.fixed(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC),
                Duration.ofMillis(200),
                Map.of(WeatherCollector.CONFIG_KEY, cfg)
        );

        collector.poll(ctx).join();

        assertTrue(store.getWeather("Boston").isPresent());
        assertTrue(store.getWeather("Nowhere").isEmpty());
        assertEquals(1, capture.byType(WeatherUpdated.class).size());

        List<AlertRaised> alerts = capture.byType(AlertRaised.class);
        assertEquals(2, alerts.size());
        AlertRaised failure = alerts.stream()
                .filter(alert -> "collector".equals(alert.category()))
                .findFirst()
                .orElseThrow();
        assertTrue(failure.message().toLowerCase(java.util.Locale.ROOT).contains("weather fetch failed"));
    }
}
