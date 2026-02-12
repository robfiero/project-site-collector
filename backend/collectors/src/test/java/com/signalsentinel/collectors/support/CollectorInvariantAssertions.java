package com.signalsentinel.collectors.support;

import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.CollectorTickStarted;
import com.signalsentinel.core.events.Event;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class CollectorInvariantAssertions {
    private CollectorInvariantAssertions() {
    }

    public static void assertTickEnvelope(EventCapture capture, String collectorName) {
        List<Event> events = capture.all();

        int startedIndex = firstIndex(events, CollectorTickStarted.class, collectorName);
        int completedIndex = firstIndex(events, CollectorTickCompleted.class, collectorName);

        assertTrue(startedIndex >= 0, "missing CollectorTickStarted for " + collectorName);
        assertTrue(completedIndex >= 0, "missing CollectorTickCompleted for " + collectorName);
        assertTrue(startedIndex < completedIndex, "tick start must happen before tick completion");

        int domainCount = events.size() - 2;
        if (domainCount > 0) {
            int firstDomain = firstDomainIndex(events);
            int lastDomain = lastDomainIndex(events);
            assertTrue(startedIndex < firstDomain, "tick start must happen before first domain event");
            assertTrue(completedIndex > lastDomain, "tick completion must happen after last domain event");
        }

        long startedCount = capture.byType(CollectorTickStarted.class).stream()
                .filter(evt -> evt.collectorName().equals(collectorName))
                .count();
        long completedCount = capture.byType(CollectorTickCompleted.class).stream()
                .filter(evt -> evt.collectorName().equals(collectorName))
                .count();

        assertEquals(1, startedCount, "expected exactly one start event for " + collectorName);
        assertEquals(1, completedCount, "expected exactly one completion event for " + collectorName);
    }

    private static int firstIndex(List<Event> events, Class<? extends Event> type, String collectorName) {
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            if (type.isInstance(event)) {
                if (event instanceof CollectorTickStarted started && started.collectorName().equals(collectorName)) {
                    return i;
                }
                if (event instanceof CollectorTickCompleted completed && completed.collectorName().equals(collectorName)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int firstDomainIndex(List<Event> events) {
        for (int i = 0; i < events.size(); i++) {
            if (!(events.get(i) instanceof CollectorTickStarted) && !(events.get(i) instanceof CollectorTickCompleted)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastDomainIndex(List<Event> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (!(events.get(i) instanceof CollectorTickStarted) && !(events.get(i) instanceof CollectorTickCompleted)) {
                return i;
            }
        }
        return -1;
    }
}
