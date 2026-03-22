package com.signalsentinel.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.CollectorTickCompleted;
import com.signalsentinel.core.events.NewsUpdated;
import com.signalsentinel.core.model.HappeningItem;
import com.signalsentinel.core.model.LocalHappeningsSignal;
import com.signalsentinel.core.model.NewsSignal;
import com.signalsentinel.core.model.NewsStory;
import com.signalsentinel.core.model.SiteSignal;
import com.signalsentinel.core.util.JsonUtils;
import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.api.CollectorResult;
import com.signalsentinel.service.store.EventCodec;
import com.signalsentinel.service.env.AirNowAqiSnapshot;
import com.signalsentinel.service.env.NoaaWeatherSnapshot;
import com.signalsentinel.service.env.ZipGeoRecord;
import com.signalsentinel.service.env.ZipGeoStore;
import com.signalsentinel.service.store.JsonFileSignalStore;
import com.signalsentinel.service.store.JsonlEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiServerIntegrationTest {
    private ApiServer apiServer;
    private static final Set<String> DEFAULT_ALLOWED_ORIGINS = Set.of(
            "http://localhost:5173",
            "https://deyyrubsvhyt8.cloudfront.net"
    );

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
    void corsAllowsKnownOrigin() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();
        String origin = "http://localhost:5173";

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/health"))
                        .header("Origin", origin)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(response.headers().firstValue("Vary").orElse("").contains("Origin"));
        assertEquals("true", response.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));
    }

    @Test
    void corsBlocksUnknownOrigin() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/health"))
                        .header("Origin", "https://unknown.example")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void corsPreflightReturnsNoContent() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();
        String origin = "http://localhost:5173";

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/health"))
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(204, response.statusCode());
        assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("GET"));
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
    void signalsEndpointReturnsLocalHappeningsPerZipStructure() throws Exception {
        TestRuntime runtime = startRuntime();
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                "02108",
                List.of(new HappeningItem(
                        "evt-1",
                        "Concert",
                        "2026-03-01T20:00:00Z",
                        "Venue",
                        "Boston",
                        "MA",
                        "https://example.com/events/1",
                        "Music",
                        "ticketmaster"
                )),
                "Powered by Ticketmaster",
                Instant.parse("2026-02-20T10:00:00Z")
        ));
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                "98101",
                List.of(new HappeningItem(
                        "evt-2",
                        "Game",
                        "2026-03-02T20:00:00Z",
                        "Arena",
                        "Seattle",
                        "WA",
                        "https://example.com/events/2",
                        "Sports",
                        "ticketmaster"
                )),
                "Powered by Ticketmaster",
                Instant.parse("2026-02-20T10:00:00Z")
        ));
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                "32830",
                List.of(new HappeningItem(
                        "evt-3",
                        "Other",
                        "2026-03-03T20:00:00Z",
                        "Venue 3",
                        "Orlando",
                        "FL",
                        "https://example.com/events/3",
                        "Music",
                        "ticketmaster"
                )),
                "Powered by Ticketmaster",
                Instant.parse("2026-02-20T10:00:00Z")
        ));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.has("localHappenings"));
        assertTrue(body.get("localHappenings").has("02108"));
        assertTrue(body.get("localHappenings").has("98101"));
        assertTrue(!body.get("localHappenings").has("32830"));
        assertEquals("ticketmaster",
                body.get("localHappenings").get("02108").get("items").get(0).get("source").asText());
    }

    @Test
    void signalsEndpointReturnsLocalHappeningsEmptyMapWhenNonePresent() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.has("localHappenings"));
        assertTrue(body.get("localHappenings").isObject());
        assertEquals(0, body.get("localHappenings").size());
    }

    @Test
    void signalsEndpointReturnsWeatherStructureEvenWhenEnvServiceUnavailable() throws Exception {
        com.signalsentinel.service.env.EnvService unavailableEnv = new com.signalsentinel.service.env.EnvService(
                new ZipGeoStore(Files.createTempDirectory("signal-sentinel-env-empty-").resolve("zip-geo.json")),
                zip -> new ZipGeoRecord(zip, 42.0, -71.0, Instant.parse("2026-02-12T20:00:00Z"), "test"),
                (lat, lon) -> {
                    throw new IllegalStateException("upstream unavailable");
                },
                zip -> java.util.Optional.empty(),
                Clock.fixed(Instant.parse("2026-02-12T20:00:00Z"), ZoneOffset.UTC),
                List.of("02108")
        );
        TestRuntime runtime = startRuntime(List.of(), Map.of(), Map.of(), unavailableEnv, false, false);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> envResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/env?zips=02108")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, envResponse.statusCode());
        JsonNode envBody = JsonUtils.objectMapper().readTree(envResponse.body());
        JsonNode weather = envBody.get(0).get("weather");
        assertTrue(weather.has("forecast"));
        assertEquals("Environment data unavailable for this ZIP right now.", weather.get("forecast").asText());

        HttpResponse<String> signalsResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, signalsResponse.statusCode());
        JsonNode signalsBody = JsonUtils.objectMapper().readTree(signalsResponse.body());
        assertTrue(signalsBody.has("weather"));
        assertTrue(signalsBody.get("weather").isObject());
    }

    @Test
    void marketsEndpointReturns501WhenServiceNotConfigured() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/markets?symbols=AAPL")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(501, response.statusCode());
        assertTrue(response.body().contains("markets_unavailable"));
    }

    @Test
    void signalsEndpointFiltersLocalHappeningsToDefaultZipCodes() throws Exception {
        TestRuntime runtime = startRuntime();
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal("02108", List.of(), "Powered by Ticketmaster", Instant.parse("2026-02-20T10:00:00Z")));
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal("98101", List.of(), "Powered by Ticketmaster", Instant.parse("2026-02-20T10:00:00Z")));
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal("32830", List.of(), "Powered by Ticketmaster", Instant.parse("2026-02-20T10:00:00Z")));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.get("localHappenings").has("02108"));
        assertTrue(body.get("localHappenings").has("98101"));
        assertFalse(body.get("localHappenings").has("32830"));
    }

    @Test
    void signalsEndpointIncludesLocalHappeningsWhenZipIsInDefaults() throws Exception {
        TestRuntime runtime = startRuntime();
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                "02108",
                List.of(new HappeningItem("evt-1", "A", "2026-03-01T20:00:00Z", "V", "Boston", "MA", "https://example.com/1", "Music", "ticketmaster")),
                "Powered by Ticketmaster",
                Instant.parse("2026-02-20T10:00:00Z")
        ));
        runtime.signalStore().putLocalHappenings(new LocalHappeningsSignal(
                "98101",
                List.of(new HappeningItem("evt-2", "B", "2026-03-02T20:00:00Z", "V2", "Seattle", "WA", "https://example.com/2", "Sports", "ticketmaster")),
                "Powered by Ticketmaster",
                Instant.parse("2026-02-20T10:00:00Z")
        ));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.get("localHappenings").has("02108"));
        assertTrue(body.get("localHappenings").has("98101"));
        assertTrue(body.get("localHappenings").get("02108").get("items").isArray());
        assertTrue(body.get("localHappenings").get("98101").get("items").isArray());
    }

    @Test
    void newsSignalsAreFilteredByDefaultSelectedSources() throws Exception {
        TestRuntime runtime = startRuntime();
        runtime.signalStore().putNews(new NewsSignal(
                "cnn",
                List.of(new NewsStory("CNN one", "https://example.com/c", Instant.parse("2026-02-12T20:00:00Z"), "cnn")),
                Instant.parse("2026-02-12T20:00:00Z")
        ));
        runtime.signalStore().putNews(new NewsSignal(
                "abc",
                List.of(new NewsStory("ABC one", "https://example.com/a", Instant.parse("2026-02-12T20:00:00Z"), "abc")),
                Instant.parse("2026-02-12T20:00:00Z")
        ));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.get("news").has("cnn"));
        assertTrue(!body.get("news").has("abc"));
    }

    @Test
    void newsSourceSettingsEndpointReturnsDefaultsForAnonymous() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/newsSources")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertTrue(body.has("availableSources"));
        assertTrue(body.has("effectiveSelectedSources"));
        assertTrue(body.get("effectiveSelectedSources").toString().contains("cnn"));
    }

    @Test
    void newsSourceSettingsEndpointReturnsAvailableSourcesContainsCnnAndAbc() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/newsSources")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode available = JsonUtils.objectMapper().readTree(response.body()).get("availableSources");
        List<String> ids = new ArrayList<>();
        for (JsonNode source : available) {
            ids.add(source.get("id").asText());
        }
        assertTrue(ids.contains("cnn"));
        assertTrue(ids.contains("abc"));
    }

    @Test
    void newsSignalsFilteringReturnsAllWhenDefaultSelectedSourcesIsEmpty() throws Exception {
        Map<String, Object> defaultsOverride = Map.of("defaultSelectedNewsSources", List.of());
        TestRuntime runtime = startRuntimeWithDefaults(defaultsOverride);
        runtime.signalStore().putNews(new NewsSignal(
                "cnn",
                List.of(new NewsStory("CNN one", "https://example.com/c", Instant.parse("2026-02-12T20:00:00Z"), "cnn")),
                Instant.parse("2026-02-12T20:00:00Z")
        ));
        runtime.signalStore().putNews(new NewsSignal(
                "abc",
                List.of(new NewsStory("ABC one", "https://example.com/a", Instant.parse("2026-02-12T20:00:00Z"), "abc")),
                Instant.parse("2026-02-12T20:00:00Z")
        ));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode news = JsonUtils.objectMapper().readTree(response.body()).get("news");
        assertEquals(2, news.size());
        assertTrue(news.has("cnn"));
        assertTrue(news.has("abc"));
    }

    @Test
    void newsSignalsFilteringIgnoresUnknownSelectedSourceIds() throws Exception {
        Map<String, Object> defaultsOverride = Map.of("defaultSelectedNewsSources", List.of("cnn", "not-real"));
        TestRuntime runtime = startRuntimeWithDefaults(defaultsOverride);
        runtime.signalStore().putNews(new NewsSignal(
                "cnn",
                List.of(new NewsStory("CNN one", "https://example.com/c", Instant.parse("2026-02-12T20:00:00Z"), "cnn")),
                Instant.parse("2026-02-12T20:00:00Z")
        ));
        runtime.signalStore().putNews(new NewsSignal(
                "abc",
                List.of(new NewsStory("ABC one", "https://example.com/a", Instant.parse("2026-02-12T20:00:00Z"), "abc")),
                Instant.parse("2026-02-12T20:00:00Z")
        ));

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/signals")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode news = JsonUtils.objectMapper().readTree(response.body()).get("news");
        assertTrue(news.has("cnn"));
        assertFalse(news.has("abc"));
        assertFalse(news.has("not-real"));
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
        String origin = "http://localhost:5173";

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/health"))
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(204, response.statusCode());
        assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
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
    void collectorsRefreshEndpointInvokesRefreshHookWithRequestedCollectors() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("siteCollector", 15), testCollector("rssCollector", 60)));
        AtomicReference<List<String>> refreshed = new AtomicReference<>(List.of());
        apiServer.setCollectorRefreshHook(names -> refreshed.set(List.copyOf(names)));
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors/refresh"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"collectors\":[\"envCollector\",\"localEventsCollector\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertEquals(List.of("envCollector", "localEventsCollector"), refreshed.get());
    }

    @Test
    void collectorsRefreshEndpointMissingCollectorsFieldUsesDefaultRefreshList() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("siteCollector", 15)));
        AtomicReference<List<String>> refreshed = new AtomicReference<>(List.of());
        apiServer.setCollectorRefreshHook(names -> refreshed.set(List.copyOf(names)));
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors/refresh"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertEquals(List.of("envCollector", "rssCollector", "localEventsCollector"), refreshed.get());
    }

    @Test
    void collectorsRefreshEndpointCollectorsNotArrayUsesDefaultRefreshList() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("siteCollector", 15)));
        AtomicReference<List<String>> refreshed = new AtomicReference<>(List.of());
        apiServer.setCollectorRefreshHook(names -> refreshed.set(List.copyOf(names)));
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors/refresh"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"collectors\":\"rssCollector\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertEquals(List.of("envCollector", "rssCollector", "localEventsCollector"), refreshed.get());
    }

    @Test
    void collectorsRefreshEndpointInvalidJsonClosesRequestWithoutRefreshHook() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("siteCollector", 15)));
        AtomicReference<List<String>> refreshed = new AtomicReference<>(List.of());
        apiServer.setCollectorRefreshHook(names -> refreshed.set(List.copyOf(names)));
        HttpClient client = HttpClient.newHttpClient();

        assertTrue(assertThrows(IOException.class, () -> client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors/refresh"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"collectors\":["))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        )).getMessage().contains("header parser received no bytes"));
        assertEquals(List.of(), refreshed.get());
    }

    @Test
    void collectorsRefreshEndpointUnknownCollectorNamesPassThrough() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("siteCollector", 15)));
        AtomicReference<List<String>> refreshed = new AtomicReference<>(List.of());
        apiServer.setCollectorRefreshHook(names -> refreshed.set(List.copyOf(names)));
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors/refresh"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"collectors\":[\"unknownCollector\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        assertEquals(List.of("unknownCollector"), refreshed.get());
    }

    @Test
    void collectorsRefreshEndpointReturns501WhenHookNotConfigured() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("siteCollector", 15)));
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors/refresh"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"collectors\":[\"siteCollector\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(501, response.statusCode());
        assertTrue(response.body().contains("collector_refresh_unavailable"));
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
    void collectorsStatusEndpointReturnsEmptyObjectWhenNoTicksYet() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("rssCollector", 60)));
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode json = JsonUtils.objectMapper().readTree(response.body());
        assertEquals(0, json.size());
    }

    @Test
    void collectorsStatusEndpointOmitsLastErrorMessageWhenNoAlertRaised() throws Exception {
        TestRuntime runtime = startRuntime(List.of(testCollector("rssCollector", 60)));
        HttpClient client = HttpClient.newHttpClient();
        runtime.eventBus().publish(new CollectorTickCompleted(
                Instant.parse("2026-02-12T20:06:00Z"),
                "rssCollector",
                true,
                120
        ));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/collectors/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode status = JsonUtils.objectMapper().readTree(response.body()).get("rssCollector");
        assertFalse(status.has("lastErrorMessage"));
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
    void configEndpointDoesNotExposeSecrets() throws Exception {
        Map<String, Object> configOverride = Map.of(
                "rss", Map.of(
                        "apiKey", "secret-token-value",
                        "password", "top-secret-password",
                        "interval", "PT60S"
                )
        );
        TestRuntime runtime = startRuntimeWithConfig(configOverride);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/config")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertFalse(body.contains("secret-token-value"));
        assertFalse(body.contains("top-secret-password"));
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
    void envEndpointMissingZipsUsesDefaultZipList() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/env")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertEquals(2, body.size());
        assertEquals("02108", body.get(0).get("zip").asText());
        assertEquals("98101", body.get(1).get("zip").asText());
    }

    @Test
    void envEndpointEmptyZipsUsesDefaultZipList() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/env?zips=")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertEquals(2, body.size());
        assertEquals("02108", body.get(0).get("zip").asText());
        assertEquals("98101", body.get(1).get("zip").asText());
    }

    @Test
    void envEndpointInvalidZipReturns400() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/env?zips=02108,abcde")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("zips must be comma-separated 5-digit ZIP codes"));
    }

    @Test
    void envEndpointTrimsAndDedupesZips() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/env?zips=02108,02108,%2098101")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());
        JsonNode body = JsonUtils.objectMapper().readTree(response.body());
        assertEquals(2, body.size());
        assertEquals("02108", body.get(0).get("zip").asText());
        assertEquals("98101", body.get(1).get("zip").asText());
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
    void preferencesEndpointReturnsAuthDisabledWhenPrefsFlagTrueAndAuthServiceMissing() throws Exception {
        TestRuntime runtime = startRuntime(List.of(), Map.of(), Map.of(), null, true, true);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> getResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, getResponse.statusCode());
        assertTrue(getResponse.body().contains("auth_disabled"));

        HttpResponse<String> putResponse = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/me/preferences"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"zipCodes\":[\"02108\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(404, putResponse.statusCode());
        assertTrue(putResponse.body().contains("auth_disabled"));
    }

    @Test
    void newsSourceSettingsPutReturnsAuthDisabledWhenAuthServiceMissingEvenForInvalidPayload() throws Exception {
        TestRuntime runtime = startRuntime(List.of(), Map.of(), Map.of(), null, true, true);
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/settings/newsSources"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"selectedSources\":\"cnn\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("auth_disabled"));
    }

    @Test
    void authEndpointsReturn405Or404ConsistentlyForWrongMethod() throws Exception {
        TestRuntime runtime = startRuntime();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> signupGet = client.send(
                HttpRequest.newBuilder(runtime.uri("/api/auth/signup")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertTrue(signupGet.statusCode() == 405 || signupGet.statusCode() == 404);
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
        return startRuntime(List.of(), Map.of(), Map.of(), null, false, false);
    }

    private TestRuntime startRuntimeWithDefaults(Map<String, Object> defaultsOverride) throws Exception {
        return startRuntime(List.of(), defaultsOverride, Map.of(), null, false, false);
    }

    private TestRuntime startRuntimeWithConfig(Map<String, Object> configOverride) throws Exception {
        return startRuntime(List.of(), Map.of(), configOverride, null, false, false);
    }

    private TestRuntime startRuntime(List<Collector> collectors) throws Exception {
        return startRuntime(collectors, Map.of(), Map.of(), null, false, false);
    }

    private TestRuntime startRuntime(
            List<Collector> collectors,
            Map<String, Object> defaultsOverride,
            Map<String, Object> configOverride,
            com.signalsentinel.service.env.EnvService envOverride,
            boolean authEnabled,
            boolean prefsEnabled
    ) throws Exception {
        Path tempDir = Files.createTempDirectory("signal-sentinel-service-it-");
        JsonFileSignalStore signalStore = new JsonFileSignalStore(tempDir.resolve("state/signals.json"));
        JsonlEventStore eventStore = new JsonlEventStore(tempDir.resolve("logs/events.jsonl"));
        EventBus eventBus = new EventBus((event, error) -> {
            throw new AssertionError("EventBus handler error", error);
        });
        EventCodec.subscribeAll(eventBus, eventStore::append);

        SseBroadcaster broadcaster = new SseBroadcaster(eventBus);
        DiagnosticsTracker diagnosticsTracker = new DiagnosticsTracker(eventBus, Clock.systemUTC(), broadcaster::clientCount);
        Map<String, Object> defaults = new java.util.LinkedHashMap<>(Map.of(
                "defaultZipCodes", List.of("02108", "98101"),
                "defaultNewsSources", List.of(
                        Map.of("id", "cnn", "name", "CNN", "type", "rss", "url", "https://example.com/cnn", "enabledByDefault", true, "requiresConfig", false, "note", ""),
                        Map.of("id", "abc", "name", "ABC News", "type", "rss", "url", "https://feeds.abcnews.com/abcnews/topstories", "enabledByDefault", false, "requiresConfig", false, "note", "")
                ),
                "defaultSelectedNewsSources", List.of("cnn"),
                "defaultWatchlist", List.of("AAPL", "MSFT", "BTC-USD")
        ));
        defaults.putAll(defaultsOverride);
        Map<String, Object> config = new java.util.LinkedHashMap<>(Map.of(
                "collectors", List.of(),
                "sites", Map.of("interval", "PT30S", "sites", List.of()),
                "rss", Map.of("interval", "PT60S", "sources", List.of()),
                "weather", Map.of("interval", "PT60S", "locations", List.of())
        ));
        config.putAll(configOverride);
        Map<String, Object> sanitizedConfig = sanitizeConfig(config);
        com.signalsentinel.service.env.EnvService envService = envOverride != null ? envOverride : new com.signalsentinel.service.env.EnvService(
                new ZipGeoStore(tempDir.resolve("data/zip-geo.json")),
                zip -> new ZipGeoRecord(zip, 42.0, -71.0, Instant.parse("2026-02-12T20:00:00Z"), "tigerweb_zcta"),
                (lat, lon) -> new NoaaWeatherSnapshot(
                        72.0,
                        "Sunny",
                        "5 mph",
                        Instant.parse("2026-02-12T20:00:00Z"),
                        "https://api.weather.gov/mock-forecast",
                        "2026-02-12T20:00:00Z",
                        "Boston",
                        "MA"
                ),
                zip -> java.util.Optional.empty(),
                Clock.fixed(Instant.parse("2026-02-12T20:00:00Z"), ZoneOffset.UTC),
                List.of("02108", "98101")
        );
        apiServer = new ApiServer(
                0,
                signalStore,
                eventStore,
                broadcaster,
                collectors,
                diagnosticsTracker,
                defaults,
                sanitizedConfig,
                null,
                envService,
                null,
                false,
                "Lax",
                prefsEnabled,
                null,
                DEFAULT_ALLOWED_ORIGINS,
                true
        );
        apiServer.start();

        return new TestRuntime(apiServer.actualPort(), signalStore, eventStore, eventBus, broadcaster);
    }

    private Map<String, Object> sanitizeConfig(Map<String, Object> config) {
        Map<String, Object> sanitized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeValue(entry.getKey(), entry.getValue()));
        }
        return sanitized;
    }

    private Object sanitizeValue(String key, Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String nestedKey = String.valueOf(entry.getKey());
                nested.put(nestedKey, sanitizeValue(nestedKey, entry.getValue()));
            }
            return nested;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                sanitized.add(sanitizeValue(key, item));
            }
            return sanitized;
        }
        String lowerKey = key.toLowerCase();
        if ((lowerKey.contains("key") || lowerKey.contains("password") || lowerKey.contains("secret")) && value != null) {
            return "[redacted]";
        }
        return value;
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
