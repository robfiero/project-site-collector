package com.signalsentinel.service.store;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonlEventStoreStressTest {
    @Test
    void concurrentPublishAppendsAllEventsWithoutHandlerErrors() throws Exception {
        int total = 500;
        Path tempDir = Files.createTempDirectory("signal-sentinel-event-store-");

        JsonlEventStore eventStore = new JsonlEventStore(tempDir.resolve("logs/events.jsonl"));
        AtomicInteger handlerErrors = new AtomicInteger();
        EventBus eventBus = new EventBus((event, error) -> handlerErrors.incrementAndGet());
        EventCodec.subscribeAll(eventBus, eventStore::append);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future<?>[total];
            for (int i = 0; i < total; i++) {
                int idx = i;
                futures[i] = executor.submit(() -> eventBus.publish(new AlertRaised(
                        Instant.parse("2026-02-12T20:00:00Z"),
                        "collector",
                        "stress-" + idx,
                        java.util.Map.of("index", idx)
                )));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertEquals(0, handlerErrors.get());
        assertEquals(total, eventStore.query(Instant.EPOCH, Optional.empty(), total).size());
    }
}
