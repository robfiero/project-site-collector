package com.signalsentinel.service.api;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.NewsUpdated;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DiagnosticsTrackerTest {

    @Test
    void recordsNewsSourceSuccesses() {
        EventBus eventBus = new EventBus();
        Instant now = Instant.parse("2026-03-20T21:10:11Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        DiagnosticsTracker tracker = new DiagnosticsTracker(eventBus, clock, () -> 0);

        eventBus.publish(new NewsUpdated(now, "ap", 7));

        Map<String, Object> snapshot = tracker.newsSourcesSnapshot();
        assertNotNull(snapshot.get("ap"));
        Map<?, ?> ap = (Map<?, ?>) snapshot.get("ap");
        assertEquals("2026-03-20T21:10:11Z", ap.get("lastSuccessAt"));
        assertEquals(7, ap.get("lastStoryCount"));
    }
}
