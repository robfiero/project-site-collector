package com.signalsentinel.core.bus;

import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.NewsUpdated;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EventBusTest {
    @Test
    void publishNotifiesMultipleSubscribersForSameType() {
        EventBus bus = new EventBus();
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();

        bus.subscribe(CollectorTickStarted.class, event -> hitsA.incrementAndGet());
        bus.subscribe(CollectorTickStarted.class, event -> hitsB.incrementAndGet());

        bus.publish(new CollectorTickStarted(Instant.parse("2026-01-01T00:00:00Z"), "siteCollector"));

        assertEquals(1, hitsA.get());
        assertEquals(1, hitsB.get());
    }

    @Test
    void publishRoutesToCorrectEventType() {
        EventBus bus = new EventBus();
        AtomicInteger tickHits = new AtomicInteger();
        AtomicInteger newsHits = new AtomicInteger();

        bus.subscribe(CollectorTickStarted.class, event -> tickHits.incrementAndGet());
        bus.subscribe(NewsUpdated.class, event -> newsHits.incrementAndGet());

        bus.publish(new CollectorTickStarted(Instant.parse("2026-01-01T00:00:00Z"), "rssCollector"));
        bus.publish(new NewsUpdated(Instant.parse("2026-01-01T00:00:01Z"), "world", 5));

        assertEquals(1, tickHits.get());
        assertEquals(1, newsHits.get());
    }

    @Test
    void publishContinuesWhenHandlerThrows() {
        AtomicReference<Exception> capturedError = new AtomicReference<>();
        EventBus bus = new EventBus((event, error) -> capturedError.set(error));
        AtomicInteger safeHits = new AtomicInteger();

        bus.subscribe(CollectorTickStarted.class, event -> {
            throw new RuntimeException("boom");
        });
        bus.subscribe(CollectorTickStarted.class, event -> safeHits.incrementAndGet());

        bus.publish(new CollectorTickStarted(Instant.parse("2026-01-01T00:00:00Z"), "weatherCollector"));

        assertEquals(1, safeHits.get());
        assertNotNull(capturedError.get());
        assertEquals("boom", capturedError.get().getMessage());
    }
}
