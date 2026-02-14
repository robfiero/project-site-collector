package com.signalsentinel.service.api;

import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.service.store.EventCodec;
import com.signalsentinel.service.store.JsonFileSignalStore;
import com.signalsentinel.service.store.JsonlEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SseIntegrationTest {
    private ApiServer apiServer;

    @AfterEach
    void tearDown() {
        if (apiServer != null) {
            apiServer.stop();
        }
    }

    @Test
    void singleConnectionReceivesThreeEventsWithoutReconnect() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<Stream<String>> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofLines()
        );
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));

        Stream<String> lines = response.body();
        CompletableFuture<List<String>> dataLinesFuture = CompletableFuture.supplyAsync(() ->
                readDataLines(lines, 3)
        );

        try {
            runtime.eventBus().publish(new AlertRaised(Instant.parse("2026-02-12T20:00:00Z"), "collector", "one", Map.of("i", 1)));
            runtime.eventBus().publish(new AlertRaised(Instant.parse("2026-02-12T20:00:01Z"), "collector", "two", Map.of("i", 2)));
            runtime.eventBus().publish(new AlertRaised(Instant.parse("2026-02-12T20:00:02Z"), "collector", "three", Map.of("i", 3)));

            List<String> frames = dataLinesFuture.get(3, TimeUnit.SECONDS);
            assertEquals(3, frames.size());
            assertTrue(frames.stream().allMatch(line -> line.contains("AlertRaised")));
        } catch (TimeoutException e) {
            fail("Timed out waiting for 3 SSE data frames");
        } finally {
            lines.close();
        }
    }

    @Test
    void disconnectingClientDoesNotBreakBroadcastOrServerResponsiveness() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<Stream<String>> sseResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofLines()
        );
        assertEquals(200, sseResponse.statusCode());

        Stream<String> lines = sseResponse.body();
        CompletableFuture<String> firstDataFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return readDataLines(lines, 1).getFirst();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            runtime.eventBus().publish(new AlertRaised(
                    Instant.parse("2026-02-12T20:00:00Z"),
                    "collector",
                    "before-close",
                    Map.of()
            ));

            String first = firstDataFuture.get(3, TimeUnit.SECONDS);
            assertTrue(first.startsWith("data:"));

            lines.close();

            assertDoesNotThrow(() -> runtime.eventBus().publish(new AlertRaised(
                    Instant.parse("2026-02-12T20:00:01Z"),
                    "collector",
                    "after-close",
                    Map.of()
            )));

            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(runtime.uri("/api/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"status\":\"ok\""));
        } catch (TimeoutException e) {
            fail("Timed out waiting for first SSE data frame");
        } finally {
            lines.close();
        }
    }

    private List<String> readDataLines(Stream<String> lines, int target) {
        List<String> dataLines = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        try {
            var iterator = lines.iterator();
            while (System.nanoTime() < deadline && dataLines.size() < target) {
                if (!iterator.hasNext()) {
                    break;
                }
                String line = iterator.next();
                if (line.startsWith("data:")) {
                    dataLines.add(line);
                }
            }
            return dataLines;
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private TestRuntime startRuntime() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-sentinel-sse-it-");
        JsonFileSignalStore signalStore = new JsonFileSignalStore(tempDir.resolve("state/signals.json"));
        JsonlEventStore eventStore = new JsonlEventStore(tempDir.resolve("logs/events.jsonl"));
        EventBus eventBus = new EventBus((event, error) -> {
            throw new AssertionError("EventBus handler error", error);
        });
        EventCodec.subscribeAll(eventBus, eventStore::append);

        SseBroadcaster broadcaster = new SseBroadcaster(eventBus);
        apiServer = new ApiServer(0, signalStore, eventStore, broadcaster, List.of());
        apiServer.start();

        return new TestRuntime(apiServer.actualPort(), eventBus);
    }

    private record TestRuntime(int port, EventBus eventBus) {
        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
