package com.signalsentinel.service.resilience;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.service.store.EventCodec;
import com.signalsentinel.service.store.JsonlEventStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResilienceTest {
    @Test
    void serializationFailureInOneSubscriberDoesNotBlockOthers() throws Exception {
        EventBus bus = new EventBus((event, error) -> {
            // Expected for the cyclic payload path in this test.
        });
        JsonlEventStore eventStore = new JsonlEventStore(Files.createTempDirectory("resilience-").resolve("logs/events.jsonl"));
        EventCodec.subscribeAll(bus, eventStore::append);

        AtomicInteger successfulSubscriber = new AtomicInteger();
        bus.subscribe(AlertRaised.class, event -> successfulSubscriber.incrementAndGet());

        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);

        bus.publish(new AlertRaised(
                Instant.parse("2026-02-12T20:00:00Z"),
                "collector",
                "cyclic payload",
                cyclic
        ));

        assertEquals(1, successfulSubscriber.get());
    }
}
