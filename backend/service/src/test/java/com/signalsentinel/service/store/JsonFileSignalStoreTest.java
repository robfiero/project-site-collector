package com.signalsentinel.service.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.signalsentinel.core.model.SiteSignal;
import com.signalsentinel.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileSignalStoreTest {
    @Test
    void roundTripPersistsAndReloadsSignals() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-store-roundtrip-");
        Path file = tempDir.resolve("state/signals.json");

        JsonFileSignalStore first = new JsonFileSignalStore(file);
        SiteSignal signal = new SiteSignal(
                "site-1",
                "https://example.com",
                "hash-1",
                "Example",
                2,
                Instant.parse("2026-02-12T20:00:00Z"),
                Instant.parse("2026-02-12T20:00:00Z")
        );
        first.putSite(signal);

        JsonFileSignalStore second = new JsonFileSignalStore(file);
        assertEquals(signal, second.getSite("site-1").orElseThrow());
    }

    @Test
    void missingStateFileInitializesEmptyStore() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-store-missing-");
        JsonFileSignalStore store = new JsonFileSignalStore(tempDir.resolve("state/missing-signals.json"));

        Map<String, Object> all = store.getAllSignals();
        assertNotNull(all.get("sites"));
        assertNotNull(all.get("news"));
        assertNotNull(all.get("weather"));
        assertEquals(0, ((Map<?, ?>) all.get("sites")).size());
        assertEquals(0, ((Map<?, ?>) all.get("news")).size());
        assertEquals(0, ((Map<?, ?>) all.get("weather")).size());
    }

    @Test
    void emptyStateFileFailsFastWithClearMessage() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-store-empty-");
        Path file = tempDir.resolve("state/signals.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new JsonFileSignalStore(file));
        assertTrue(ex.getMessage().contains("Failed loading signals"));
    }

    @Test
    void corruptStateFileFailsFastWithClearMessage() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-store-corrupt-");
        Path file = tempDir.resolve("state/signals.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not valid json }");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new JsonFileSignalStore(file));
        assertTrue(ex.getMessage().contains("Failed loading signals"));
    }

    @Test
    void concurrentSignalUpdatesKeepFileValidAndLatestReadable() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-store-concurrent-");
        Path file = tempDir.resolve("state/signals.json");
        JsonFileSignalStore store = new JsonFileSignalStore(file);

        int total = 100;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future<?>[total];
            for (int i = 0; i < total; i++) {
                int idx = i;
                futures[i] = executor.submit(() -> store.putSite(new SiteSignal(
                        "site-1",
                        "https://example.com",
                        "hash-" + idx,
                        "Title-" + idx,
                        idx,
                        Instant.parse("2026-02-12T20:00:00Z"),
                        Instant.parse("2026-02-12T20:00:00Z")
                )));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        // Force a known latest value after concurrent writes.
        SiteSignal latest = new SiteSignal(
                "site-1",
                "https://example.com",
                "hash-final",
                "Title-final",
                999,
                Instant.parse("2026-02-12T20:00:00Z"),
                Instant.parse("2026-02-12T20:00:00Z")
        );
        store.putSite(latest);

        String rawJson = Files.readString(file);
        JsonNode parsed = JsonUtils.objectMapper().readTree(rawJson);
        assertTrue(parsed.has("sites"));

        JsonFileSignalStore reloaded = new JsonFileSignalStore(file);
        assertEquals(latest, reloaded.getSite("site-1").orElseThrow());
    }

    @Test
    void putSiteOverwritesSameIdAndKeepsOtherSites() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-store-overwrite-");
        Path file = tempDir.resolve("state/signals.json");
        JsonFileSignalStore store = new JsonFileSignalStore(file);

        SiteSignal original = new SiteSignal(
                "site-1",
                "https://example.com",
                "hash-1",
                "Title 1",
                1,
                Instant.parse("2026-02-12T20:00:00Z"),
                Instant.parse("2026-02-12T20:00:00Z")
        );
        SiteSignal updated = new SiteSignal(
                "site-1",
                "https://example.com",
                "hash-2",
                "Title 2",
                2,
                Instant.parse("2026-02-12T20:01:00Z"),
                Instant.parse("2026-02-12T20:01:00Z")
        );
        SiteSignal other = new SiteSignal(
                "site-2",
                "https://example.org",
                "hash-x",
                "Other",
                3,
                Instant.parse("2026-02-12T20:02:00Z"),
                Instant.parse("2026-02-12T20:02:00Z")
        );

        store.putSite(original);
        store.putSite(other);
        store.putSite(updated);

        JsonFileSignalStore reloaded = new JsonFileSignalStore(file);
        assertEquals(updated, reloaded.getSite("site-1").orElseThrow());
        assertEquals(other, reloaded.getSite("site-2").orElseThrow());
        Map<String, Object> all = reloaded.getAllSignals();
        assertEquals(2, ((Map<?, ?>) all.get("sites")).size());
    }

    @Test
    void concurrentDifferentSiteWritesProduceValidJsonAndAllIds() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-store-concurrent-ids-");
        Path file = tempDir.resolve("state/signals.json");
        JsonFileSignalStore store = new JsonFileSignalStore(file);

        int total = 50;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future<?>[total];
            for (int i = 0; i < total; i++) {
                int idx = i;
                futures[i] = executor.submit(() -> store.putSite(new SiteSignal(
                        "site-" + idx,
                        "https://example.com/" + idx,
                        "hash-" + idx,
                        "Title-" + idx,
                        idx,
                        Instant.parse("2026-02-12T20:00:00Z"),
                        Instant.parse("2026-02-12T20:00:00Z")
                )));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        JsonNode parsed = JsonUtils.objectMapper().readTree(Files.readString(file));
        assertTrue(parsed.has("sites"));

        JsonFileSignalStore reloaded = new JsonFileSignalStore(file);
        Map<String, Object> all = reloaded.getAllSignals();
        assertEquals(total, ((Map<?, ?>) all.get("sites")).size());
        for (int i = 0; i < total; i++) {
            assertTrue(reloaded.getSite("site-" + i).isPresent());
        }
    }

    @Test
    void unwritableTargetFailsFastWithClearMessage() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-store-unwritable-");
        Path blocker = tempDir.resolve("not-a-dir");
        Files.writeString(blocker, "blocker");

        JsonFileSignalStore store = new JsonFileSignalStore(blocker.resolve("signals.json"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                store.putSite(new SiteSignal(
                        "site-1",
                        "https://example.com",
                        "h",
                        "t",
                        0,
                        Instant.EPOCH,
                        Instant.EPOCH
                ))
        );
        assertTrue(ex.getMessage().contains("Failed writing signals"));
    }
}
