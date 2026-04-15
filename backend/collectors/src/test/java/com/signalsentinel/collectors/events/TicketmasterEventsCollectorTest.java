package com.signalsentinel.collectors.events;

import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.config.TicketmasterCollectorConfig;
import com.signalsentinel.collectors.support.EventCapture;
import com.signalsentinel.collectors.support.InMemorySignalStore;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.model.HappeningItem;
import com.signalsentinel.core.model.LocalHappeningsSignal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketmasterEventsCollectorTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testPollIteratesEffectiveZipList_andCallsApiPerZip() throws Exception {
        List<URI> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/custom-base/events.json", exchange -> {
            requests.add(exchange.getRequestURI());
            writeResponse(exchange, 200, emptyEventsJson());
        });
        server.start();

        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext context = context(
                new InMemorySignalStore(),
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "api-key-123",
                                "http://localhost:" + server.getAddress().getPort() + "/custom-base",
                                () -> List.of("02108", "98101"),
                                25,
                                List.of("music")
                        )
                )
        );

        var result = collector.poll(context).join();
        assertTrue(result.success());
        assertEquals(2, requests.size());

        Map<String, String> q1 = queryParams(requests.get(0));
        Map<String, String> q2 = queryParams(requests.get(1));
        assertTrue(postalCodes(requests).contains("02108"));
        assertTrue(postalCodes(requests).contains("98101"));
        assertEquals("US", q1.get("countryCode"));
        assertEquals("US", q2.get("countryCode"));
        assertEquals("api-key-123", q1.get("apikey"));
        assertEquals("api-key-123", q2.get("apikey"));
        assertEquals("/custom-base/events.json", requests.get(0).getPath());
        assertEquals("/custom-base/events.json", requests.get(1).getPath());
    }

    @Test
    void testWritesOneSignalPerZip_withExpectedNormalizedFields() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/events.json", exchange -> {
            String zip = queryParams(exchange.getRequestURI()).get("postalCode");
            writeResponse(exchange, 200, twoEventsJson(zip, false, false));
        });
        server.start();

        InMemorySignalStore store = new InMemorySignalStore();
        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext context = context(
                store,
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "test-key",
                                "http://localhost:" + server.getAddress().getPort(),
                                () -> List.of("02108", "98101"),
                                25,
                                List.of()
                        )
                )
        );

        var result = collector.poll(context).join();
        assertTrue(result.success());

        LocalHappeningsSignal zip1 = store.getLocalHappenings("02108").orElseThrow();
        LocalHappeningsSignal zip2 = store.getLocalHappenings("98101").orElseThrow();
        assertEquals(2, zip1.items().size());
        assertEquals(2, zip2.items().size());
        assertAscending(zip1.items());
        assertAscending(zip2.items());
        assertRequiredFields(zip1.items());
        assertRequiredFields(zip2.items());
    }

    @Test
    void testDedupeByEventIdWithinZip() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/events.json", exchange -> writeResponse(exchange, 200, twoEventsJson("02108", true, false)));
        server.start();

        InMemorySignalStore store = new InMemorySignalStore();
        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext context = context(
                store,
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "test-key",
                                "http://localhost:" + server.getAddress().getPort(),
                                () -> List.of("02108"),
                                25,
                                List.of()
                        )
                )
        );

        var result = collector.poll(context).join();
        assertTrue(result.success());
        LocalHappeningsSignal signal = store.getLocalHappenings("02108").orElseThrow();
        assertEquals(1, signal.items().size());
    }

    @Test
    void testOneZipFailureDoesNotPreventOtherZipSuccess_andPreservesExistingDataForFailedZip() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/events.json", exchange -> {
            String zip = queryParams(exchange.getRequestURI()).get("postalCode");
            if ("02108".equals(zip)) {
                writeResponse(exchange, 500, "{\"error\":\"boom\"}");
            } else {
                writeResponse(exchange, 200, twoEventsJson(zip, false, false));
            }
        });
        server.start();

        InMemorySignalStore store = new InMemorySignalStore();
        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext context = context(
                store,
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "test-key",
                                "http://localhost:" + server.getAddress().getPort(),
                                () -> List.of("02108", "98101"),
                                25,
                                List.of()
                        )
                )
        );

        var result = collector.poll(context).join();
        assertFalse(result.success());
        // Failed ZIP should not overwrite existing data — with no prior data, store has no entry.
        assertTrue(store.getLocalHappenings("02108").isEmpty(), "failed ZIP should not write empty signal");
        assertEquals(2, store.getLocalHappenings("98101").orElseThrow().items().size());
    }

    @Test
    void testAuthFailureShortCircuits() throws Exception {
        List<URI> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/events.json", exchange -> {
            requests.add(exchange.getRequestURI());
            writeResponse(exchange, 403, "{\"fault\":\"forbidden\"}");
        });
        server.start();

        InMemorySignalStore store = new InMemorySignalStore();
        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected event handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext context = new CollectorContext(
                HttpClient.newHttpClient(),
                bus,
                store,
                Clock.fixed(Instant.parse("2026-02-20T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(2),
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "test-key",
                                "http://localhost:" + server.getAddress().getPort(),
                                () -> List.of("02108", "98101"),
                                25,
                                List.of()
                        )
                )
        );

        var result = collector.poll(context).join();
        assertFalse(result.success());
        assertEquals(1, requests.size(), "collector should stop after first auth failure in the same poll");
        assertTrue(capture.byType(AlertRaised.class).stream().anyMatch(a -> a.message().contains("unauthorized")));
        assertTrue(store.getLocalHappenings("02108").isEmpty());
        assertTrue(store.getLocalHappenings("98101").isEmpty());
    }

    @Test
    void testMissingApiKeyDisablesCollector() {
        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext context = context(
                new InMemorySignalStore(),
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "",
                                "https://app.ticketmaster.com/discovery/v2",
                                () -> List.of("02108"),
                                25,
                                List.of()
                        )
                )
        );

        var result = collector.poll(context).join();
        assertTrue(result.success());
        assertTrue(result.message().contains("missing TICKETMASTER_API_KEY"));
    }

    @Test
    void emptyEventsWritesEmptySignalPerZip() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/events.json", exchange -> writeResponse(exchange, 200, emptyEventsJson()));
        server.start();

        InMemorySignalStore store = new InMemorySignalStore();
        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext context = context(
                store,
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "test-key",
                                "http://localhost:" + server.getAddress().getPort(),
                                () -> List.of("02108", "98101"),
                                25,
                                List.of()
                        )
                )
        );

        var result = collector.poll(context).join();
        assertTrue(result.success());
        assertEquals(0, store.getLocalHappenings("02108").orElseThrow().items().size());
        assertEquals(0, store.getLocalHappenings("98101").orElseThrow().items().size());
    }

    @Test
    void missingEmbeddedVenuesOrFieldsIsHandled() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/events.json", exchange -> writeResponse(exchange, 200, missingVenueFieldsJson()));
        server.start();

        InMemorySignalStore store = new InMemorySignalStore();
        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext context = context(
                store,
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "test-key",
                                "http://localhost:" + server.getAddress().getPort(),
                                () -> List.of("02108"),
                                25,
                                List.of()
                        )
                )
        );

        var result = collector.poll(context).join();
        assertTrue(result.success());
        LocalHappeningsSignal signal = store.getLocalHappenings("02108").orElseThrow();
        assertEquals(1, signal.items().size());
        HappeningItem item = signal.items().getFirst();
        assertEquals("Unknown venue", item.venueName());
        assertEquals("Unknown city", item.city());
        assertEquals("", item.state());
    }

    @Test
    void classificationFilteringBranch_includesParamWhenConfigured_andIncludesAllItems() throws Exception {
        List<URI> requests = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/events.json", exchange -> {
            requests.add(exchange.getRequestURI());
            writeResponse(exchange, 200, twoEventsJson("02108", false, false));
        });
        server.start();

        InMemorySignalStore store = new InMemorySignalStore();
        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext filteredContext = context(
                store,
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "test-key",
                                "http://localhost:" + server.getAddress().getPort(),
                                () -> List.of("02108"),
                                25,
                                List.of("music")
                        )
                )
        );

        var filteredResult = collector.poll(filteredContext).join();
        assertTrue(filteredResult.success());
        assertEquals("music", queryParams(requests.getFirst()).get("classificationName"));
        assertEquals(2, store.getLocalHappenings("02108").orElseThrow().items().size());

        requests.clear();
        InMemorySignalStore unfilteredStore = new InMemorySignalStore();
        CollectorContext unfilteredContext = context(
                unfilteredStore,
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "test-key",
                                "http://localhost:" + server.getAddress().getPort(),
                                () -> List.of("98101"),
                                25,
                                List.of()
                        )
                )
        );

        var unfilteredResult = collector.poll(unfilteredContext).join();
        assertTrue(unfilteredResult.success());
        assertFalse(queryParams(requests.getFirst()).containsKey("classificationName"));
        assertEquals(2, unfilteredStore.getLocalHappenings("98101").orElseThrow().items().size());
    }

    @Test
    void rateLimitStatusOnOneZipDoesNotBlockOtherZip() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/events.json", exchange -> {
            String zip = queryParams(exchange.getRequestURI()).get("postalCode");
            if ("02108".equals(zip)) {
                writeResponse(exchange, 429, "{\"error\":\"rate limited\"}");
                return;
            }
            writeResponse(exchange, 200, twoEventsJson(zip, false, false));
        });
        server.start();

        InMemorySignalStore store = new InMemorySignalStore();
        TicketmasterEventsCollector collector = new TicketmasterEventsCollector(Duration.ofSeconds(60));
        CollectorContext context = context(
                store,
                Map.of(
                        TicketmasterEventsCollector.CONFIG_KEY,
                        new TicketmasterCollectorConfig(
                                "test-key",
                                "http://localhost:" + server.getAddress().getPort(),
                                () -> List.of("02108", "98101"),
                                25,
                                List.of()
                        )
                )
        );

        var result = collector.poll(context).join();
        assertFalse(result.success());
        // Rate-limited ZIP should not overwrite existing data — with no prior data, store has no entry.
        assertTrue(store.getLocalHappenings("02108").isEmpty(), "rate-limited ZIP should not write empty signal");
        assertEquals(2, store.getLocalHappenings("98101").orElseThrow().items().size());
    }

    private static CollectorContext context(InMemorySignalStore store, Map<String, Object> cfg) {
        return new CollectorContext(
                HttpClient.newHttpClient(),
                new EventBus((event, error) -> {
                    throw new AssertionError("Unexpected event handler error", error);
                }),
                store,
                Clock.fixed(Instant.parse("2026-02-20T10:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(2),
                cfg
        );
    }

    private static void assertRequiredFields(List<HappeningItem> items) {
        for (HappeningItem item : items) {
            assertTrue(item.id() != null && !item.id().isBlank());
            assertTrue(item.name() != null && !item.name().isBlank());
            assertTrue(item.startDateTime() != null && !item.startDateTime().isBlank());
            Instant.parse(item.startDateTime());
            assertTrue(item.venueName() != null && !item.venueName().isBlank());
            assertTrue(item.city() != null && !item.city().isBlank());
            assertTrue(item.state() != null);
            assertTrue(item.url() != null && !item.url().isBlank());
            assertEquals("ticketmaster", item.source());
        }
    }

    private static void assertAscending(List<HappeningItem> items) {
        List<Instant> times = items.stream().map(i -> Instant.parse(i.startDateTime())).toList();
        for (int i = 1; i < times.size(); i++) {
            assertTrue(!times.get(i).isBefore(times.get(i - 1)));
        }
    }

    private static List<String> postalCodes(List<URI> requests) {
        List<String> values = new ArrayList<>();
        for (URI request : requests) {
            values.add(queryParams(request).get("postalCode"));
        }
        return values;
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getQuery();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    private static String emptyEventsJson() {
        return "{\"_embedded\":{\"events\":[]}}";
    }

    private static String twoEventsJson(String zip, boolean duplicateId, boolean reverseChronological) {
        String idOne = zip + "-evt-1";
        String idTwo = duplicateId ? idOne : zip + "-evt-2";
        String first = """
                {
                  "id":"%s",
                  "name":"%s Show A",
                  "url":"https://tickets.example/%s/a",
                  "dates":{"start":{"dateTime":"2026-03-10T20:00:00Z"}},
                  "classifications":[{"segment":{"name":"Music"}}],
                  "_embedded":{"venues":[{"name":"Venue A","city":{"name":"City %s"},"state":{"stateCode":"MA"}}]}
                }
                """.formatted(idOne, zip, zip, zip);
        String second = """
                {
                  "id":"%s",
                  "name":"%s Show B",
                  "url":"https://tickets.example/%s/b",
                  "dates":{"start":{"dateTime":"2026-03-08T20:00:00Z"}},
                  "classifications":[{"segment":{"name":"Sports"}}],
                  "_embedded":{"venues":[{"name":"Venue B","city":{"name":"City %s"},"state":{"stateCode":"MA"}}]}
                }
                """.formatted(idTwo, zip, zip, zip);
        return reverseChronological
                ? "{\"_embedded\":{\"events\":[" + first + "," + second + "]}}"
                : "{\"_embedded\":{\"events\":[" + second + "," + first + "]}}";
    }

    private static String missingVenueFieldsJson() {
        return """
                {
                  "_embedded": {
                    "events": [
                      {
                        "id": "evt-missing-venue",
                        "name": "No Venue Event",
                        "url": "https://tickets.example/no-venue",
                        "dates": {"start": {"dateTime": "2026-03-10T20:00:00Z"}},
                        "classifications": [{"segment": {"name": "Music"}}]
                      }
                    ]
                  }
                }
                """;
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
