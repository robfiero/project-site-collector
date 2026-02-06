package com.signalsentinel.core.bus;

import com.signalsentinel.core.events.CollectorTickStarted;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusConcurrencyTest {
    @Test
    void concurrentPublishInvokesAllSubscribers() throws Exception {
        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("No handler should fail in this test", error);
        });

        int subscriberCount = 8;
        int publishCount = 1_000;
        LongAdder invocations = new LongAdder();

        for (int i = 0; i < subscriberCount; i++) {
            bus.subscribe(CollectorTickStarted.class, event -> invocations.increment());
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future<?>[publishCount];
            for (int i = 0; i < publishCount; i++) {
                futures[i] = executor.submit(() ->
                        bus.publish(new CollectorTickStarted(Instant.now(), "siteCollector")));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertEquals((long) subscriberCount * publishCount, invocations.sum());
    }

    @Test
    void concurrentSubscribeAndPublishKeepsPublishingAndStaysStable() throws Exception {
        AtomicInteger handlerErrors = new AtomicInteger();
        EventBus bus = new EventBus((event, error) -> handlerErrors.incrementAndGet());

        AtomicInteger observedEvents = new AtomicInteger();
        bus.subscribe(CollectorTickStarted.class, event -> observedEvents.incrementAndGet());

        int publisherTasks = 4;
        int publishesPerTask = 1_000;
        int subscriberTasks = 4;
        int subscriptionsPerTask = 150;
        int totalPublishes = publisherTasks * publishesPerTask;

        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] tasks = new Future<?>[publisherTasks + subscriberTasks];
            int index = 0;

            for (int i = 0; i < publisherTasks; i++) {
                tasks[index++] = executor.submit(() -> {
                    start.await();
                    for (int j = 0; j < publishesPerTask; j++) {
                        bus.publish(new CollectorTickStarted(Instant.now(), "rssCollector"));
                    }
                    return null;
                });
            }

            for (int i = 0; i < subscriberTasks; i++) {
                tasks[index++] = executor.submit(() -> {
                    start.await();
                    for (int j = 0; j < subscriptionsPerTask; j++) {
                        bus.subscribe(CollectorTickStarted.class, event -> observedEvents.incrementAndGet());
                    }
                    return null;
                });
            }

            start.countDown();

            for (Future<?> task : tasks) {
                task.get();
            }
        }

        assertEquals(0, handlerErrors.get());
        assertTrue(observedEvents.get() >= totalPublishes);
    }
}
