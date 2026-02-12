package com.signalsentinel.collectors.site;

import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
import com.signalsentinel.collectors.support.EventCapture;
import com.signalsentinel.collectors.support.InMemorySignalStore;
import com.signalsentinel.collectors.support.MutableClock;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.ContentChanged;
import com.signalsentinel.core.events.SiteFetched;
import com.signalsentinel.core.model.ParseMode;
import com.signalsentinel.core.model.SiteConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteCollectorTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void detectsContentChangesAcrossPolls() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("<html><head><title>V1</title></head><body><a href='a'>a</a></body></html>");
        startServer(exchange -> writeResponse(exchange, 200, body.get()));

        SiteConfig site = new SiteConfig("site-1", "http://localhost:" + server.getAddress().getPort() + "/page", List.of("prod"), ParseMode.TITLE);
        SiteCollectorConfig siteConfig = new SiteCollectorConfig(Duration.ofSeconds(30), List.of(site));

        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC);

        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                store,
                clock,
                Duration.ofSeconds(1),
                Map.of(SiteCollector.CONFIG_KEY, siteConfig)
        );

        SiteCollector collector = new SiteCollector();

        collector.poll(ctx).join();
        assertEquals(0, capture.byType(ContentChanged.class).size());

        body.set("<html><head><title>V2</title></head><body><a href='a'>a</a></body></html>");
        clock.setInstant(Instant.parse("2026-02-09T20:00:10Z"));
        collector.poll(ctx).join();

        List<ContentChanged> changes = capture.byType(ContentChanged.class);
        List<SiteFetched> fetched = capture.byType(SiteFetched.class);

        assertEquals(1, changes.size());
        assertEquals(2, fetched.size());
        assertTrue(store.getSite("site-1").isPresent());
        assertEquals("V2", store.getSite("site-1").get().title());
        assertEquals(Instant.parse("2026-02-09T20:00:10Z"), store.getSite("site-1").get().lastChanged());
    }

    @Test
    void parallelFetchesDoNotCorruptState() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int totalSites = 20;
        int port = server.getAddress().getPort();

        List<SiteConfig> sites = IntStream.range(0, totalSites)
                .mapToObj(index -> {
                    String path = "/site-" + index;
                    int delay = (index % 4 == 0) ? 120 : 10;
                    server.createContext(path, exchange -> {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        writeResponse(exchange, 200, "<html><head><title>S" + index + "</title></head><body>OK</body></html>");
                    });
                    return new SiteConfig("site-" + index, "http://localhost:" + port + path, List.of("batch"), ParseMode.TITLE);
                })
                .toList();
        server.start();

        SiteCollectorConfig siteConfig = new SiteCollectorConfig(Duration.ofSeconds(30), sites);
        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();

        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                store,
                Clock.fixed(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(1),
                Map.of(SiteCollector.CONFIG_KEY, siteConfig)
        );

        SiteCollector collector = new SiteCollector();
        collector.poll(ctx).join();

        List<SiteFetched> fetched = capture.byType(SiteFetched.class);
        assertEquals(totalSites, fetched.size());
        assertEquals(0, capture.byType(ContentChanged.class).size());

        Set<String> expectedIds = sites.stream().map(SiteConfig::id).collect(java.util.stream.Collectors.toSet());
        Set<String> seenIds = new HashSet<>();
        for (SiteFetched event : fetched) {
            seenIds.add(event.siteId());
            assertTrue(store.getSite(event.siteId()).isPresent());
        }
        assertEquals(totalSites, seenIds.size());
        assertEquals(expectedIds, seenIds);
    }

    @Test
    void secondRunWithSameInputIsIdempotent() throws Exception {
        startServer(exchange -> writeResponse(exchange, 200, "<html><head><title>Stable</title></head><body><a href='a'>a</a></body></html>"));

        SiteConfig site = new SiteConfig("stable-site", "http://localhost:" + server.getAddress().getPort() + "/page", List.of("prod"), ParseMode.TITLE);
        SiteCollectorConfig siteConfig = new SiteCollectorConfig(Duration.ofSeconds(30), List.of(site));

        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();

        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                store,
                Clock.fixed(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(1),
                Map.of(SiteCollector.CONFIG_KEY, siteConfig)
        );

        SiteCollector collector = new SiteCollector();
        collector.poll(ctx).join();
        collector.poll(ctx).join();

        assertEquals(0, capture.byType(ContentChanged.class).size());
        assertEquals(2, capture.byType(SiteFetched.class).size());
    }

    @Test
    void timeoutOnOneEndpointDoesNotBlockOthers() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        int port = server.getAddress().getPort();

        server.createContext("/ok-1", exchange -> writeResponse(exchange, 200, "<html><head><title>OK1</title></head><body>ok</body></html>"));
        server.createContext("/ok-2", exchange -> writeResponse(exchange, 200, "<html><head><title>OK2</title></head><body>ok</body></html>"));
        server.createContext("/hang", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeResponse(exchange, 200, "<html><head><title>Late</title></head><body>late</body></html>");
        });
        server.start();

        List<SiteConfig> sites = List.of(
                new SiteConfig("ok-1", "http://localhost:" + port + "/ok-1", List.of("timeout"), ParseMode.TITLE),
                new SiteConfig("ok-2", "http://localhost:" + port + "/ok-2", List.of("timeout"), ParseMode.TITLE),
                new SiteConfig("hang", "http://localhost:" + port + "/hang", List.of("timeout"), ParseMode.TITLE)
        );
        SiteCollectorConfig siteConfig = new SiteCollectorConfig(Duration.ofSeconds(30), sites);

        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();

        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build(),
                bus,
                store,
                Clock.fixed(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC),
                Duration.ofMillis(100),
                Map.of(SiteCollector.CONFIG_KEY, siteConfig)
        );

        SiteCollector collector = new SiteCollector();
        var result = collector.poll(ctx).join();

        List<SiteFetched> fetched = capture.byType(SiteFetched.class);
        assertEquals(3, fetched.size());
        assertEquals(1, fetched.stream().filter(event -> event.status() == 0).count());
        assertEquals(2, fetched.stream().filter(event -> event.status() == 200).count());
        assertTrue(store.getSite("ok-1").isPresent());
        assertTrue(store.getSite("ok-2").isPresent());
        assertTrue(store.getSite("hang").isEmpty());
        assertTrue(!result.success());
    }

    @Test
    void invalidHostEmitsCollectorAlertWithDnsContext() {
        SiteConfig bad = new SiteConfig("bad-host", "http://does-not-exist.invalid/path", List.of("err"), ParseMode.RAW_HASH);
        SiteCollectorConfig siteConfig = new SiteCollectorConfig(Duration.ofSeconds(30), List.of(bad));

        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();

        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build(),
                bus,
                store,
                Clock.fixed(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC),
                Duration.ofMillis(200),
                Map.of(SiteCollector.CONFIG_KEY, siteConfig)
        );

        SiteCollector collector = new SiteCollector();
        collector.poll(ctx).join();

        List<AlertRaised> alerts = capture.byType(AlertRaised.class);
        assertEquals(1, alerts.size());
        assertEquals("collector", alerts.getFirst().category());
        String message = alerts.getFirst().message().toLowerCase(java.util.Locale.ROOT);
        assertTrue(message.contains("dns") || message.contains("unknown host"));
    }

    @Test
    void http500ProducesStatusEventAndCollectorContinues() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/ok", exchange -> writeResponse(exchange, 200, "<html><head><title>ok</title></head></html>"));
        server.createContext("/err", exchange -> writeResponse(exchange, 500, "<html><head><title>err</title></head></html>"));
        server.start();

        List<SiteConfig> sites = List.of(
                new SiteConfig("ok", "http://localhost:" + port + "/ok", List.of("status"), ParseMode.TITLE),
                new SiteConfig("err", "http://localhost:" + port + "/err", List.of("status"), ParseMode.TITLE)
        );
        SiteCollectorConfig siteConfig = new SiteCollectorConfig(Duration.ofSeconds(30), sites);

        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();

        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                store,
                Clock.fixed(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(1),
                Map.of(SiteCollector.CONFIG_KEY, siteConfig)
        );

        SiteCollector collector = new SiteCollector();
        collector.poll(ctx).join();

        List<SiteFetched> fetched = capture.byType(SiteFetched.class);
        assertEquals(2, fetched.size());
        assertEquals(1, fetched.stream().filter(event -> event.status() == 500).count());
        assertEquals(1, fetched.stream().filter(event -> event.status() == 200).count());
        assertTrue(store.getSite("ok").isPresent());
        assertTrue(store.getSite("err").isEmpty());
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/page", exchange -> handler.handle(exchange));
        server.start();
    }

    private static void writeResponse(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
