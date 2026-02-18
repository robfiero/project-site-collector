package com.signalsentinel.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.NewsUpdated;
import com.signalsentinel.core.model.SiteSignal;
import com.signalsentinel.core.util.JsonUtils;
import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.service.store.EventCodec;
import com.signalsentinel.service.env.AirNowAqiSnapshot;
import com.signalsentinel.service.env.EnvService;
import com.signalsentinel.service.env.NoaaWeatherSnapshot;
import com.signalsentinel.service.env.ZipGeoRecord;
import com.signalsentinel.service.env.ZipGeoStore;
import com.signalsentinel.service.store.JsonFileSignalStore;
import com.signalsentinel.service.store.JsonlEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerIntegrationTest {
    private ApiServer apiServer;

    @AfterEach
    void tearDown() {
        if (apiServer != null) {
            apiServer.stop();
        }
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"ok\""));
    }

    @Test
    void signalsEndpointReturnsValidJsonWhenEmpty() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.has("sites"));
        assertTrue(body.has("news"));
        assertTrue(body.has("weather"));
    }

    @Test
    void signalsEndpointReturnsSnapshotJson() throws Exception {
        TestRuntime runtime = startRuntime();
        runtime.signalStore().putSite(new SiteSignal(
                "site-a",
                "https://example.com",
                "abc123",
                "Example",
                1,
                Instant.parse("2026-02-12T20:00:00Z"),
                Instant.parse("2026-02-12T20:00:00Z")
        ));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"sites\""));
        assertTrue(response.body().contains("site-a"));
    }

    @Test
    void eventsEndpointSupportsDefaultSinceTypeAndLimitAndInvalidParams() throws Exception {
        TestRuntime runtime = startRuntime();
        runtime.eventBus().publish(new AlertRaised(
                Instant.parse("2026-02-12T20:00:00Z"),
                "collector",
                "a",
                Map.of("idx", 1)
        ));
        runtime.eventBus().publish(new NewsUpdated(
                Instant.parse("2026-02-12T20:01:00Z"),
                "feed",
                2
        ));
        runtime.eventBus().publish(new AlertRaised(
                Instant.parse("2026-02-12T20:02:00Z"),
                "collector",
                "b",
                Map.of("idx", 2)
        ));

        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> defaultResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/events")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, defaultResponse.statusCode());
        assertEquals(3, JsonUtils.objectMapper().readTree(defaultResponse.body()).size());

        HttpResponse<String> sinceResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/events?since=2026-02-12T20:00:30Z")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, sinceResponse.statusCode());
        assertEquals(2, JsonUtils.objectMapper().readTree(sinceResponse.body()).size());

        HttpResponse<String> typeResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/events?type=AlertRaised")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, typeResponse.statusCode());
        assertEquals(2, JsonUtils.objectMapper().readTree(typeResponse.body()).size());

        HttpResponse<String> limitResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/events?limit=1")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, limitResponse.statusCode());
        assertEquals(1, JsonUtils.objectMapper().readTree(limitResponse.body()).size());

        HttpResponse<String> invalidResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/events?since=not-an-instant&limit=nope")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(400, invalidResponse.statusCode());
        assertTrue(invalidResponse.body().contains("invalid_query_params"));
    }

    @Test
    void corsPreflightReturnsExpectedHeaders() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/health"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(204, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("GET"));
    }

    @Test
    void collectorsEndpointReturnsCollectorMetadata() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("siteCollector", 15), testCollector("rssCollector", 60)));
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertEquals(2, body.size());
        assertEquals("siteCollector", body.get(0).get("name").asText());
        assertEquals(15, body.get(0).get("intervalSeconds").asInt());
    }

    @Test
    void metricsEndpointReturnsRequiredFieldsAndUpdatesFromEvents() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("siteCollector", 15)));
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> before = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, before.statusCode());
        JsonNode beforeJson = JsonUtils.objectMapper().readTree(before.body());
        assertTrue(beforeJson.has("sseClientsConnected"));
        assertTrue(beforeJson.has("eventsEmittedTotal"));
        assertTrue(beforeJson.has("recentEventsPerMinute"));
        assertTrue(beforeJson.has("collectors"));

        runtime.eventBus().publish(new CollectorTickCompleted(
                Instant.parse("2026-02-12T20:05:00Z"),
                "siteCollector",
                true,
                250
        ));
        runtime.eventBus().publish(new AlertRaised(
                Instant.parse("2026-02-12T20:05:01Z"),
                "collector",
                "collector warning",
                Map.of("collector", "siteCollector")
        ));

        HttpResponse<String> after = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, after.statusCode());
        JsonNode afterJson = JsonUtils.objectMapper().readTree(after.body());
        assertTrue(afterJson.get("eventsEmittedTotal").asLong() >= 2);
        assertTrue(afterJson.get("recentEventsPerMinute").asInt() >= 2);
        JsonNode collectorStatus = afterJson.get("collectors").get("siteCollector");
        assertNotNull(collectorStatus);
        assertEquals(true, collectorStatus.get("lastSuccess").asBoolean());
        assertEquals(250, collectorStatus.get("lastDurationMillis").asInt());
    }

    @Test
    void collectorsStatusEndpointReflectsCollectorTickEvents() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("rssCollector", 60)));
        HttpClient client = HttpClient.newHttpClient();

        runtime.eventBus().publish(new CollectorTickCompleted(
                Instant.parse("2026-02-12T20:06:00Z"),
                "rssCollector",
                false,
                500
        ));
        runtime.eventBus().publish(new AlertRaised(
                Instant.parse("2026-02-12T20:06:01Z"),
                "collector",
                "rss failed",
                Map.of("collector", "rssCollector")
        ));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());
        JsonNode json = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(json.has("rssCollector"));
        JsonNode status = json.get("rssCollector");
        assertEquals(false, status.get("lastSuccess").asBoolean());
        assertEquals(500, status.get("lastDurationMillis").asInt());
        assertTrue(status.get("lastErrorMessage").asText().contains("rss failed"));
    }

    @Test
    void catalogDefaultsEndpointReturnsStableDefaults() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/catalog/defaults")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.has("defaultZipCodes"));
        assertTrue(body.has("defaultNewsSources"));
        assertTrue(body.has("defaultWatchlist"));
        assertTrue(body.get("defaultWatchlist").isArray());
    }

    @Test
    void configEndpointReturnsSanitizedConfigView() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/config")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.has("collectors"));
        assertTrue(body.has("sites"));
        assertTrue(body.has("rss"));
        assertTrue(body.has("weather"));
    }

    @Test
    void envEndpointReturnsEnvironmentForRequestedZips() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/env?zips=02108,98101")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertEquals(2, body.size());
        assertEquals("02108", body.get(0).get("zip").asText());
        assertEquals(72.0, body.get(0).get("weather").get("temperatureF").asDouble());
        assertEquals("AQI unavailable", body.get(0).get("aqi").get("message").asText());
    }

    @Test
    void authEndpointsReturnNotFoundWhenAuthDisabled() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> signupResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/auth/signup"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@example.com\",\"password\":\"secret\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, signupResponse.statusCode());

        HttpResponse<String> meResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, meResponse.statusCode());

        HttpResponse<String> prefsResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, prefsResponse.statusCode());

        HttpResponse<String> logoutResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/auth/logout"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, logoutResponse.statusCode());
    }

    @Test
    void nonGetMethodsReturn405() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> signalsPost = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(405, signalsPost.statusCode());

        HttpResponse<String> eventsPost = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/events"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(405, eventsPost.statusCode());

        HttpResponse<String> collectorsPost = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(405, collectorsPost.statusCode());

        HttpResponse<String> streamPost = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/stream"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(405, streamPost.statusCode());
    }

    @Test
    void eventsEndpointInvalidSinceReturns400() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> invalidSince = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/events?since=not-an-instant")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(400, invalidSince.statusCode());
        assertTrue(invalidSince.body().contains("invalid_query_params"));
    }

    @Test
    void eventsEndpointInvalidLimitReturns400() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> invalidLimit = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/events?limit=not-a-number")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(400, invalidLimit.statusCode());
        assertTrue(invalidLimit.body().contains("invalid_query_params"));
    }

    @Test
    void lifecycleStartOnZeroUsesEphemeralPortAndStopIsIdempotent() throws Exception {
        Path tempDir = Files.createTempDirectory("signal-sentinel-service-lifecycle-");
        JsonFileSignalStore signalStore = new JsonFileSignalStore(tempDir.resolve("state/signals.json"));
        JsonlEventStore eventStore = new JsonlEventStore(tempDir.resolve("logs/events.jsonl"));
        EventBus eventBus = new EventBus((event, error) -> {
            throw new AssertionError("EventBus handler error", error);
        });
        EventCodec.subscribeAll(eventBus, eventStore::append);
        SseBroadcaster broadcaster = new SseBroadcaster(eventBus);
        ApiServer lifecycleServer = new ApiServer(0, signalStore, eventStore, broadcaster, List.of());

        lifecycleServer.start();
        assertTrue(lifecycleServer.actualPort() > 0);
        assertDoesNotThrow(lifecycleServer::stop);
        assertDoesNotThrow(lifecycleServer::stop);
    }

    @Test
    void sseResponseHasEventStreamHeaders() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("keep-alive", response.headers().firstValue("Connection").orElse(""));
        response.body().close();
    }

    @Test
    void sseStreamReceivesPublishedEvent() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertEquals(200, response.statusCode());

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<String> dataLineFuture = CompletableFuture.supplyAsync(
                () -> readDataLines(response.body(), 1).getFirst(),
                readerExecutor
        );

        try {
            runtime.eventBus().publish(new AlertRaised(
                    Instant.parse("2026-02-12T20:00:00Z"),
                    "collector",
                    "SSE integration test",
                    Map.of("test", true)
            ));

            String dataLine = dataLineFuture.get(3, TimeUnit.SECONDS);
            assertNotNull(dataLine);
            assertTrue(dataLine.startsWith("data:"));
            assertTrue(dataLine.contains("AlertRaised"));
        } finally {
            response.body().close();
            readerExecutor.shutdownNow();
        }
    }

    @Test
    void sseStreamDeliversMultipleEventsOnSingleConnection() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<InputStream> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(
                () -> readDataLines(response.body(), 3),
                readerExecutor
        );

        try {
            runtime.eventBus().publish(new AlertRaised(Instant.parse("2026-02-12T20:00:00Z"), "collector", "e1", Map.of()));
            runtime.eventBus().publish(new AlertRaised(Instant.parse("2026-02-12T20:00:01Z"), "collector", "e2", Map.of()));
            runtime.eventBus().publish(new AlertRaised(Instant.parse("2026-02-12T20:00:02Z"), "collector", "e3", Map.of()));

            List<String> lines = future.get(3, TimeUnit.SECONDS);
            assertEquals(3, lines.size());
            assertTrue(lines.stream().allMatch(line -> line.startsWith("data:")));
        } finally {
            response.body().close();
            readerExecutor.shutdownNow();
        }
    }

    @Test
    void closingOneClientDoesNotBreakDeliveryToOtherClients() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<InputStream> first = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        HttpResponse<InputStream> second = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        first.body().close();

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> readDataLines(second.body(), 1).getFirst(),
                readerExecutor
        );

        try {
            runtime.eventBus().publish(new AlertRaised(
                    Instant.parse("2026-02-12T20:00:00Z"),
                    "collector",
                    "after-close",
                    Map.of()
            ));

            String data = future.get(3, TimeUnit.SECONDS);
            assertTrue(data.contains("AlertRaised"));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline && runtime.broadcaster().clientCount() > 1) {
                runtime.eventBus().publish(new AlertRaised(
                        Instant.parse("2026-02-12T20:00:00Z"),
                        "collector",
                        "cleanup-probe",
                        Map.of()
                ));
                Thread.sleep(10);
            }
            assertTrue(runtime.broadcaster().clientCount() <= 1);
        } finally {
            second.body().close();
            readerExecutor.shutdownNow();
        }
    }

    private List<String> readDataLines(InputStream input, int expectedCount) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    lines.add(line);
                    if (lines.size() >= expectedCount) {
                        return lines;
                    }
                }
            }
        } catch (Exception ignored) {
            return lines;
        }
        return lines;
    }

    private TestRuntime startRuntime() throws Exception {
        return startRuntime(List.of());
    }

    private TestRuntime startRuntime(List<Collector> collectors) throws Exception {
        Path tempDir = Files.createTempDirectory("signal-sentinel-service-it-");
        JsonFileSignalStore signalStore = new JsonFileSignalStore(tempDir.resolve("state/signals.json"));
        JsonlEventStore eventStore = new JsonlEventStore(tempDir.resolve("logs/events.jsonl"));
        EventBus eventBus = new EventBus((event, error) -> {
            throw new AssertionError("EventBus handler error", error);
        });
        EventCodec.subscribeAll(eventBus, eventStore::append);

        SseBroadcaster broadcaster = new SseBroadcaster(eventBus);
        DiagnosticsTracker diagnosticsTracker = new DiagnosticsTracker(eventBus, Clock.systemUTC(), broadcaster::clientCount);
        Map<String, Object> defaults = Map.of(
                "defaultZipCodes", List.of("02108", "98101"),
                "defaultNewsSources", List.of(Map.of("id", "demo", "name", "Demo", "url", "https://example.com/feed", "category", "general")),
                "defaultWatchlist", List.of("AAPL", "MSFT", "BTC-USD")
        );
        Map<String, Object> config = Map.of(
                "collectors", List.of(),
                "sites", Map.of("interval", "PT30S", "sites", List.of()),
                "rss", Map.of("interval", "PT60S", "sources", List.of()),
                "weather", Map.of("interval", "PT60S", "locations", List.of())
        );
        EnvService envService = new EnvService(
                new ZipGeoStore(tempDir.resolve("data/zip-geo.json")),
                zip -> new ZipGeoRecord(zip, 42.0, -71.0, Instant.parse("2026-02-12T20:00:00Z"), "tigerweb_zcta"),
                (lat, lon) -> new NoaaWeatherSnapshot(
                        72.0,
                        "Sunny",
                        "5 mph",
                        Instant.parse("2026-02-12T20:00:00Z"),
                        "https://api.weather.gov/mock-forecast",
                        "2026-02-12T20:00:00Z"
                ),
                zip -> java.util.Optional.empty(),
                Clock.fixed(Instant.parse("2026-02-12T20:00:00Z"), ZoneOffset.UTC),
                List.of("02108", "98101")
        );
        apiServer = new ApiServer(0, signalStore, eventStore, broadcaster, collectors, diagnosticsTracker, defaults, config, null, envService, false, false, null);
        apiServer.start();

        return new TestRuntime(apiServer.actualPort(), signalStore, eventStore, eventBus, broadcaster);
    }

    private record TestRuntime(
            int port,
            JsonFileSignalStore signalStore,
            JsonlEventStore eventStore,
            EventBus eventBus,
            SseBroadcaster broadcaster
    ) {
        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }

    private Collector testCollector(String name, long intervalSeconds) {
        return new Collector() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Duration interval() {
                return Duration.ofSeconds(intervalSeconds);
            }

            @Override
            public CompletableFuture<CollectorResult> poll(CollectorContext ctx) {
                return CompletableFuture.completedFuture(CollectorResult.success("ok", Map.of()));
            }
        };
    }
}
