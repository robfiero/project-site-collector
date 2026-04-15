package com.signalsentinel.service.store;

import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.Event;
import com.signalsentinel.core.events.LoginFailed;
import com.signalsentinel.core.events.LoginSucceeded;
import com.signalsentinel.core.events.PasswordResetFailed;
import com.signalsentinel.core.events.PasswordResetRequested;
import com.signalsentinel.core.events.PasswordResetSucceeded;
import com.signalsentinel.core.events.UserRegistered;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlEventStoreTest {
    @Test
    void roundTripAppendsAndReloadsEvents() throws Exception {
        Path tempDir = Files.createTempDirectory("event-store-roundtrip-");
        Path file = tempDir.resolve("logs/events.jsonl");

        JsonlEventStore store = new JsonlEventStore(file);
        AlertRaised event = alert("first", Instant.parse("2026-02-12T20:00:00Z"));
        store.append(event);

        JsonlEventStore reloaded = new JsonlEventStore(file);
        List<Event> events = reloaded.query(Instant.EPOCH, Optional.empty(), 10);
        assertEquals(1, events.size());
        assertEquals("AlertRaised", events.getFirst().type());
    }

    @Test
    void appendOnlyIntegrityWritesNValidJsonLines() throws Exception {
        Path tempDir = Files.createTempDirectory("event-store-integrity-");
        Path file = tempDir.resolve("logs/events.jsonl");
        JsonlEventStore store = new JsonlEventStore(file);

        int total = 20;
        for (int i = 0; i < total; i++) {
            store.append(alert("e" + i, Instant.parse("2026-02-12T20:00:00Z").plusSeconds(i)));
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(total, lines.size());
        for (String line : lines) {
            Event parsed = EventCodec.fromJsonLine(line);
            assertEquals("AlertRaised", parsed.type());
        }
    }

    @Test
    void querySupportsSinceTypeAndLimit() throws Exception {
        Path tempDir = Files.createTempDirectory("event-store-query-");
        JsonlEventStore store = new JsonlEventStore(tempDir.resolve("logs/events.jsonl"));

        store.append(alert("a", Instant.parse("2026-02-12T20:00:00Z")));
        store.append(alert("b", Instant.parse("2026-02-12T20:01:00Z")));
        store.append(new com.signalsentinel.core.events.NewsUpdated(
                Instant.parse("2026-02-12T20:02:00Z"),
                "feed",
                3
        ));

        assertEquals(2, store.query(Instant.parse("2026-02-12T20:00:30Z"), Optional.empty(), 10).size());
        assertEquals(2, store.query(Instant.EPOCH, Optional.of("AlertRaised"), 10).size());
        assertEquals(1, store.query(Instant.EPOCH, Optional.empty(), 1).size());
    }

    @Test
    void missingLogFileQueriesAsEmpty() throws Exception {
        Path tempDir = Files.createTempDirectory("event-store-missing-");
        JsonlEventStore store = new JsonlEventStore(tempDir.resolve("logs/missing-events.jsonl"));
        assertEquals(0, store.query(Instant.EPOCH, Optional.empty(), 10).size());
    }

    @Test
    void invalidJsonLineFailsFastAtLoadTime() throws Exception {
        Path tempDir = Files.createTempDirectory("event-store-corrupt-");
        Path file = tempDir.resolve("logs/events.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"not\":\"valid event\"}\nnot-json\n", StandardCharsets.UTF_8);

        // Exception is thrown at construction time (during cache load), not at query time.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new JsonlEventStore(file)
        );
        assertTrue(ex.getMessage().contains("Invalid JSONL event at line"));
    }

    @Test
    void startupLoadsOnlyLastNCachedEvents() throws Exception {
        Path tempDir = Files.createTempDirectory("event-store-capacity-");
        Path file = tempDir.resolve("logs/events.jsonl");
        JsonlEventStore writer = new JsonlEventStore(file, 5);

        for (int i = 0; i < 10; i++) {
            writer.append(alert("e" + i, Instant.EPOCH.plusSeconds(i)));
        }

        // Reopen with a capacity of 5 — should only load the last 5 events.
        JsonlEventStore reader = new JsonlEventStore(file, 5);
        List<Event> loaded = reader.query(Instant.EPOCH, Optional.empty(), 100);
        assertEquals(5, loaded.size());
        // The loaded events should be the last 5 (e5..e9).
        assertEquals("e5", ((com.signalsentinel.core.events.AlertRaised) loaded.getFirst()).message());
        assertEquals("e9", ((com.signalsentinel.core.events.AlertRaised) loaded.getLast()).message());
    }

    @Test
    void unwritablePathFailsFastWithClearMessage() throws Exception {
        Path tempDir = Files.createTempDirectory("event-store-unwritable-");
        Path blocker = tempDir.resolve("not-a-dir");
        Files.writeString(blocker, "blocker");

        JsonlEventStore store = new JsonlEventStore(blocker.resolve("events.jsonl"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                store.append(alert("x", Instant.EPOCH))
        );
        assertTrue(ex.getMessage().contains("Failed appending event"));
    }

    @Test
    void concurrentAppendKeepsCountAndNoMalformedLines() throws Exception {
        Path tempDir = Files.createTempDirectory("event-store-concurrent-");
        Path file = tempDir.resolve("logs/events.jsonl");
        JsonlEventStore store = new JsonlEventStore(file);

        int total = 300;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future<?>[total];
            for (int i = 0; i < total; i++) {
                int idx = i;
                futures[i] = executor.submit(() -> store.append(alert("event-" + idx, Instant.EPOCH.plusSeconds(idx))));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(total, lines.size());
        for (String line : lines) {
            EventCodec.fromJsonLine(line);
        }
    }

    /**
     * End-to-end PII regression test: verifies that raw email addresses are NEVER written to the
     * event log file for any of the six auth event types. EventCodec.sanitize() masks emails before
     * serialisation; this test confirms that masking is applied on the path from event object all
     * the way to bytes on disk.
     */
    @Test
    void rawEmailNeverWrittenToLogFileForAuthEvents() throws Exception {
        Path tempDir = Files.createTempDirectory("event-store-pii-");
        Path file = tempDir.resolve("logs/events.jsonl");
        JsonlEventStore store = new JsonlEventStore(file);

        String rawEmail = "shiela@example.com";
        String maskedEmail = "s***a@example.com";
        Instant t = Instant.parse("2026-02-12T20:00:00Z");

        store.append(new UserRegistered(t, "uid-1", rawEmail));
        store.append(new LoginSucceeded(t, "uid-1", rawEmail));
        store.append(new LoginFailed(t, rawEmail, "bad password"));
        store.append(new PasswordResetRequested(t, rawEmail));
        store.append(new PasswordResetSucceeded(t, "uid-1", rawEmail));
        store.append(new PasswordResetFailed(t, rawEmail, "expired"));

        String fileContent = Files.readString(file, StandardCharsets.UTF_8);

        assertFalse(fileContent.contains(rawEmail),
                "Raw email must never be written to the event log file");
        assertTrue(fileContent.contains(maskedEmail),
                "Masked email must appear in the event log file");

        // Reload confirms the in-memory cache also contains only masked emails.
        JsonlEventStore reloaded = new JsonlEventStore(file);
        List<Event> events = reloaded.query(Instant.EPOCH, Optional.empty(), 100);
        assertEquals(6, events.size());
        for (Event event : events) {
            String serialized = EventCodec.toJsonLine(event);
            assertFalse(serialized.contains(rawEmail),
                    "Raw email must not appear in serialized event of type " + event.type());
        }
    }

    private AlertRaised alert(String message, Instant timestamp) {
        return new AlertRaised(timestamp, "collector", message, java.util.Map.of("m", message));
    }
}
