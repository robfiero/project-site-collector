package com.signalsentinel.service.api;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsTrackerTest {
    @Test
    void tracksMetricsAndCollectorStatusFromEvents() {
        EventBus eventBus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected bus error", error);
        });
        DiagnosticsTracker tracker = new DiagnosticsTracker(
                eventBus,
                Clock.fixed(Instant.parse("2026-02-15T00:00:00Z"), ZoneOffset.UTC),
                () -> 2
        );

        eventBus.publish(new CollectorTickStarted(Instant.parse("2026-02-15T00:00:00Z"), "siteCollector"));
        eventBus.publish(new CollectorTickCompleted(Instant.parse("2026-02-15T00:00:01Z"), "siteCollector", false, 321));
        eventBus.publish(new AlertRaised(
                Instant.parse("2026-02-15T00:00:02Z"),
                "collector",
                "siteCollector failed",
                Map.of("collector", "siteCollector")
        ));

        Map<String, Object> metrics = tracker.metricsSnapshot();
        assertEquals(2, metrics.get("sseClientsConnected"));
        assertTrue(((Number) metrics.get("eventsEmittedTotal")).longValue() >= 3L);
        assertTrue(((Number) metrics.get("recentEventsPerMinute")).intValue() >= 3);

        Map<?, ?> collectors = (Map<?, ?>) metrics.get("collectors");
        Map<?, ?> status = (Map<?, ?>) collectors.get("siteCollector");
        assertEquals(false, status.get("lastSuccess"));
        assertEquals(321L, status.get("lastDurationMillis"));
        assertTrue(String.valueOf(status.get("lastErrorMessage")).contains("failed"));
    }
}
