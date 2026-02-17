package com.signalsentinel.service.api;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.Event;
import com.signalsentinel.service.store.EventCodec;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

public final class DiagnosticsTracker {
    private final Clock clock;
    private final IntSupplier sseClientCountSupplier;
    private final LongAdder eventsEmittedTotal = new LongAdder();
    private final ArrayDeque<Instant> recentEventTimestamps = new ArrayDeque<>();
    private final Object recentLock = new Object();
    private final ConcurrentHashMap<String, CollectorStatus> collectorStatuses = new ConcurrentHashMap<>();

    public DiagnosticsTracker(EventBus eventBus, Clock clock, IntSupplier sseClientCountSupplier) {
        this(clock, sseClientCountSupplier);
        EventCodec.subscribeAll(eventBus, this::onAnyEvent);
        eventBus.subscribe(CollectorTickStarted.class, this::onTickStarted);
        eventBus.subscribe(CollectorTickCompleted.class, this::onTickCompleted);
        eventBus.subscribe(AlertRaised.class, this::onAlertRaised);
    }

    private DiagnosticsTracker(Clock clock, IntSupplier sseClientCountSupplier) {
        this.clock = clock;
        this.sseClientCountSupplier = sseClientCountSupplier;
    }

    public static DiagnosticsTracker empty() {
        return new DiagnosticsTracker(Clock.systemUTC(), () -> 0);
    }

    public Map<String, Object> metricsSnapshot() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("sseClientsConnected", sseClientCountSupplier.getAsInt());
        metrics.put("eventsEmittedTotal", eventsEmittedTotal.longValue());
        metrics.put("recentEventsPerMinute", recentEventsPerMinute());
        metrics.put("collectors", collectorsSnapshot());
        return metrics;
    }

    public Map<String, Object> collectorsSnapshot() {
        Map<String, Object> collectors = new HashMap<>();
        for (Map.Entry<String, CollectorStatus> entry : collectorStatuses.entrySet()) {
            collectors.put(entry.getKey(), entry.getValue().toMap());
        }
        return collectors;
    }

    private void onAnyEvent(Event event) {
        eventsEmittedTotal.increment();
        Instant now = clock.instant();
        synchronized (recentLock) {
            recentEventTimestamps.addLast(now);
            trimOld(now);
        }
    }

    private int recentEventsPerMinute() {
        synchronized (recentLock) {
            trimOld(clock.instant());
            return recentEventTimestamps.size();
        }
    }

    private void trimOld(Instant now) {
        Instant threshold = now.minus(1, ChronoUnit.MINUTES);
        while (!recentEventTimestamps.isEmpty()) {
            Instant first = recentEventTimestamps.peekFirst();
            if (first != null && first.isBefore(threshold)) {
                recentEventTimestamps.removeFirst();
            } else {
                break;
            }
        }
    }

    private void onTickStarted(CollectorTickStarted event) {
        collectorStatuses.compute(event.collectorName(), (name, current) -> {
            CollectorStatus status = current == null ? CollectorStatus.empty() : current;
            return status.withLastRunAt(event.timestamp());
        });
    }

    private void onTickCompleted(CollectorTickCompleted event) {
        collectorStatuses.compute(event.collectorName(), (name, current) -> {
            CollectorStatus status = current == null ? CollectorStatus.empty() : current;
            return status.withCompletion(event.timestamp(), event.durationMillis(), event.success());
        });
    }

    private void onAlertRaised(AlertRaised event) {
        if (!"collector".equalsIgnoreCase(event.category()) || event.details() == null) {
            return;
        }
        Object collector = event.details().get("collector");
        if (!(collector instanceof String collectorName) || collectorName.isBlank()) {
            return;
        }
        collectorStatuses.compute(collectorName, (name, current) -> {
            CollectorStatus status = current == null ? CollectorStatus.empty() : current;
            return status.withLastErrorMessage(event.message());
        });
    }

    private record CollectorStatus(
            Instant lastRunAt,
            Long lastDurationMillis,
            Boolean lastSuccess,
            String lastErrorMessage
    ) {
        private static CollectorStatus empty() {
            return new CollectorStatus(null, null, null, null);
        }

        private CollectorStatus withLastRunAt(Instant runAt) {
            return new CollectorStatus(runAt, lastDurationMillis, lastSuccess, lastErrorMessage);
        }

        private CollectorStatus withCompletion(Instant runAt, long durationMillis, boolean success) {
            return new CollectorStatus(runAt, durationMillis, success, success ? null : lastErrorMessage);
        }

        private CollectorStatus withLastErrorMessage(String message) {
            return new CollectorStatus(lastRunAt, lastDurationMillis, lastSuccess, message);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("lastRunAt", lastRunAt == null ? null : lastRunAt.toString());
            map.put("lastDurationMillis", lastDurationMillis);
            map.put("lastSuccess", lastSuccess);
            map.put("lastErrorMessage", lastErrorMessage);
            return map;
        }
    }
}
