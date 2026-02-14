package com.signalsentinel.service.runtime;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.core.events.AlertRaised;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SchedulerService {
    private final List<ScheduledCollector> collectors;
    private final CollectorContext context;
    private final long minIntervalMillis;
    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService collectorExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SchedulerService(List<ScheduledCollector> collectors, CollectorContext context) {
        this(collectors, context, 100);
    }

    SchedulerService(List<ScheduledCollector> collectors, CollectorContext context, long minIntervalMillis) {
        this.collectors = List.copyOf(collectors);
        this.context = context;
        this.minIntervalMillis = minIntervalMillis;
    }

    public void start() {
        for (ScheduledCollector scheduled : collectors) {
            if (!scheduled.enabled()) {
                continue;
            }
            long intervalMillis = Math.max(minIntervalMillis, scheduled.interval().toMillis());
            timerExecutor.scheduleAtFixedRate(
                    () -> collectorExecutor.submit(() -> runCollector(scheduled.collector())),
                    0,
                    intervalMillis,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public List<CollectorResult> runOnceAllCollectors() {
        try (var scope = java.util.concurrent.StructuredTaskScope.open()) {
            List<java.util.concurrent.StructuredTaskScope.Subtask<CollectorResult>> tasks = new ArrayList<>();
            for (ScheduledCollector scheduled : collectors) {
                if (!scheduled.enabled()) {
                    continue;
                }
                tasks.add(scope.fork(() -> runCollectorSafely(scheduled.collector())));
            }
            scope.join();

            List<CollectorResult> results = new ArrayList<>();
            for (var task : tasks) {
                results.add(task.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    public void shutdown() {
        timerExecutor.shutdown();
        collectorExecutor.shutdown();
        try {
            timerExecutor.awaitTermination(5, TimeUnit.SECONDS);
            collectorExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<ScheduledCollector> scheduledCollectors() {
        return collectors;
    }

    private void runCollector(Collector collector) {
        runCollectorSafely(collector);
    }

    private CollectorResult runCollectorSafely(Collector collector) {
        try {
            return collector.poll(context).join();
        } catch (Exception ex) {
            context.eventBus().publish(new AlertRaised(
                    context.clock().instant(),
                    "collector",
                    "Collector run failed: " + collector.name() + " - " + ex.getMessage(),
                    java.util.Map.of("collector", collector.name())
            ));
            return CollectorResult.failure(
                    "Collector run failed: " + collector.name(),
                    java.util.Map.of("collector", collector.name())
            );
        }
    }

    public record ScheduledCollector(Collector collector, Duration interval, boolean enabled) {
        public ScheduledCollector {
            Objects.requireNonNull(collector, "collector is required");
            Objects.requireNonNull(interval, "interval is required");
        }
    }
}
