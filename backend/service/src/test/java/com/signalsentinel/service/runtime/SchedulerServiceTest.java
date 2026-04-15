package com.signalsentinel.service.runtime;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.service.support.TestSignalStore;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerServiceTest {
    @Test
    void runOnceAllCollectorsRunsEnabledCollectorsAndReturns() {
        AtomicInteger runsA = new AtomicInteger();
        AtomicInteger runsB = new AtomicInteger();

        Collector a = collector("a", () -> {
            runsA.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });
        Collector b = collector("b", () -> {
            runsB.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });

        SchedulerService scheduler = new SchedulerService(
                List.of(
                        new SchedulerService.ScheduledCollector(a, Duration.ofMillis(10), true),
                        new SchedulerService.ScheduledCollector(b, Duration.ofMillis(10), false)
                ),
                context(new EventBus())
        );

        List<CollectorResult> results = scheduler.runOnceAllCollectors();
        assertEquals(1, results.size());
        assertEquals(1, runsA.get());
        assertEquals(0, runsB.get());
    }

    @Test
    void failureInOneCollectorDoesNotBlockOthersAndEmitsAlert() {
        EventBus bus = new EventBus();
        List<AlertRaised> alerts = new CopyOnWriteArrayList<>();
        bus.subscribe(AlertRaised.class, alerts::add);

        AtomicInteger goodRuns = new AtomicInteger();
        Collector bad = collector("bad", () -> {
            throw new RuntimeException("boom");
        });
        Collector good = collector("good", () -> {
            goodRuns.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });

        SchedulerService scheduler = new SchedulerService(
                List.of(
                        new SchedulerService.ScheduledCollector(bad, Duration.ofMillis(10), true),
                        new SchedulerService.ScheduledCollector(good, Duration.ofMillis(10), true)
                ),
                context(bus)
        );

        List<CollectorResult> results = scheduler.runOnceAllCollectors();
        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(result -> !result.success()));
        assertTrue(results.stream().anyMatch(CollectorResult::success));
        assertEquals(1, goodRuns.get());
        assertTrue(alerts.stream().anyMatch(alert -> alert.message().contains("Collector run failed: bad")));
    }

    @Test
    void shutdownStopsFutureScheduling() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Collector collector = collector("scheduled", () -> {
            runs.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });

        SchedulerService scheduler = new SchedulerService(
                List.of(new SchedulerService.ScheduledCollector(collector, Duration.ofMillis(5), true)),
                context(new EventBus()),
                5
        );

        scheduler.start();
        Thread.sleep(30);
        scheduler.shutdown();
        int shortlyAfterShutdown = runs.get();
        Thread.sleep(40);
        int settledAfterShutdown = runs.get();

        assertTrue(shortlyAfterShutdown > 0);
        // At most one already-submitted in-flight run may finish after shutdown.
        assertTrue(settledAfterShutdown <= shortlyAfterShutdown + 1);
    }

    @Test
    void repeatedRunOnceCompletesPredictably() {
        AtomicInteger runs = new AtomicInteger();
        Collector collector = collector("repeat", () -> {
            runs.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });

        SchedulerService scheduler = new SchedulerService(
                List.of(new SchedulerService.ScheduledCollector(collector, Duration.ofMillis(10), true)),
                context(new EventBus())
        );

        for (int i = 0; i < 50; i++) {
            List<CollectorResult> results = scheduler.runOnceAllCollectors();
            assertEquals(1, results.size());
            assertFalse(results.getFirst().message().isBlank());
        }

        assertEquals(50, runs.get());
    }

    @Test
    void startThenShutdownIsIdempotent() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Collector collector = collector("idempotent", () -> {
            runs.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });

        SchedulerService scheduler = new SchedulerService(
                List.of(new SchedulerService.ScheduledCollector(collector, Duration.ofMillis(5), true)),
                context(new EventBus()),
                5
        );

        scheduler.start();
        Thread.sleep(20);
        scheduler.shutdown();
        scheduler.shutdown();
        int afterShutdown = runs.get();
        Thread.sleep(20);
        assertTrue(runs.get() <= afterShutdown + 1);
    }

    @Test
    void disabledCollectorIsNotScheduled() throws Exception {
        AtomicInteger enabledRuns = new AtomicInteger();
        AtomicInteger disabledRuns = new AtomicInteger();
        Collector enabled = collector("enabled", () -> {
            enabledRuns.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });
        Collector disabled = collector("disabled", () -> {
            disabledRuns.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });

        SchedulerService scheduler = new SchedulerService(
                List.of(
                        new SchedulerService.ScheduledCollector(enabled, Duration.ofMillis(5), true),
                        new SchedulerService.ScheduledCollector(disabled, Duration.ofMillis(5), false)
                ),
                context(new EventBus()),
                5
        );

        scheduler.start();
        Thread.sleep(30);
        scheduler.shutdown();

        assertTrue(enabledRuns.get() > 0);
        assertEquals(0, disabledRuns.get());
    }

    @Test
    void overlappingCollectorRunIsSkipped() throws Exception {
        CountDownLatch firstRunStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        AtomicInteger runCount = new AtomicInteger();

        Collector slow = collector("slow", () -> {
            firstRunStarted.countDown();
            runCount.incrementAndGet();
            releaseFirstRun.await(5, TimeUnit.SECONDS);
            return CollectorResult.success("ok", Map.of());
        });

        SchedulerService scheduler = new SchedulerService(
                List.of(new SchedulerService.ScheduledCollector(slow, Duration.ofMillis(5), true)),
                context(new EventBus()),
                5
        );

        scheduler.start();
        assertTrue(firstRunStarted.await(2, TimeUnit.SECONDS), "First run should start promptly");

        // Let ~12 intervals elapse while the first run is still blocked.
        Thread.sleep(60);
        assertEquals(1, runCount.get(), "Subsequent ticks must be skipped while first run is in progress");

        releaseFirstRun.countDown();
        Thread.sleep(30);
        scheduler.shutdown();
    }

    @Test
    void runOnceCollectorsRunsOnlyRequestedNames() {
        AtomicInteger runsA = new AtomicInteger();
        AtomicInteger runsB = new AtomicInteger();
        Collector a = collector("a", () -> {
            runsA.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });
        Collector b = collector("b", () -> {
            runsB.incrementAndGet();
            return CollectorResult.success("ok", Map.of());
        });

        SchedulerService scheduler = new SchedulerService(
                List.of(
                        new SchedulerService.ScheduledCollector(a, Duration.ofMillis(10), true),
                        new SchedulerService.ScheduledCollector(b, Duration.ofMillis(10), true)
                ),
                context(new EventBus())
        );

        List<CollectorResult> results = scheduler.runOnceCollectors(List.of("b"));
        assertEquals(1, results.size());
        assertEquals(0, runsA.get());
        assertEquals(1, runsB.get());
    }

    private Collector collector(String name, java.util.concurrent.Callable<CollectorResult> behavior) {
        return new Collector() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration interval() {
                return Duration.ofSeconds(1);
            }

            @Override
            public CompletableFuture<CollectorResult> poll(CollectorContext ctx) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return behavior.call();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        };
    }

    private CollectorContext context(EventBus bus) {
        return new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build(),
                bus,
                new TestSignalStore(),
                Clock.fixed(Instant.parse("2026-02-12T20:00:00Z"), ZoneOffset.UTC),
                Duration.ofMillis(200),
                Map.of()
        );
    }
}
