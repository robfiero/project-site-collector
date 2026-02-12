package com.signalsentinel.collectors.rss;

import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.RssSourceConfig;
import com.signalsentinel.collectors.support.EventCapture;
import com.signalsentinel.collectors.support.FixtureUtils;
import com.signalsentinel.collectors.support.InMemorySignalStore;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.NewsUpdated;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesRssFixtureAndRaisesKeywordAlert() throws Exception {
        String rss = Files.readString(FixtureUtils.fixturePath("fixtures/sample-rss.xml"), StandardCharsets.UTF_8);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rss", exchange -> writeResponse(exchange, rss));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                3,
                List.of("storm"),
                List.of(new RssSourceConfig("local-news", "http://localhost:" + server.getAddress().getPort() + "/rss"))
        );

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
                Map.of(RssNewsCollector.CONFIG_KEY, cfg)
        );

        RssNewsCollector collector = new RssNewsCollector();
        collector.poll(ctx).join();

        assertTrue(store.getNews("local-news").isPresent());
        assertEquals(3, store.getNews("local-news").get().stories().size());
        assertEquals(1, capture.byType(NewsUpdated.class).size());
        assertEquals(1, capture.byType(AlertRaised.class).size());
    }

    @Test
    void parsesAtomFixture() throws Exception {
        String atom = Files.readString(FixtureUtils.fixturePath("fixtures/sample-atom.xml"), StandardCharsets.UTF_8);

        List<com.signalsentinel.core.model.NewsStory> stories = RssNewsCollector.parseStories(atom, "atom-source");
        assertEquals(2, stories.size());
        assertEquals("Atom headline one", stories.getFirst().title());
    }

    @Test
    void rssVariantsHandleMissingFieldsAndDates() throws Exception {
        String rss = Files.readString(FixtureUtils.fixturePath("fixtures/rss-variants.xml"), StandardCharsets.UTF_8);

        List<com.signalsentinel.core.model.NewsStory> stories = RssNewsCollector.parseStories(rss, "rss-variants");
        assertEquals(4, stories.size());

        assertEquals("Standard story", stories.get(0).title());
        assertEquals("<b>Storm</b> warning", stories.get(1).title());
        assertEquals("(untitled)", stories.get(2).title());
        assertEquals(Instant.EPOCH, stories.get(1).publishedAt());
        assertEquals(Instant.EPOCH, stories.get(3).publishedAt());
        assertTrue(stories.stream().allMatch(story -> story.source().equals("rss-variants")));
    }

    @Test
    void atomVariantsHandleMissingFieldsAndDates() throws Exception {
        String atom = Files.readString(FixtureUtils.fixturePath("fixtures/atom-variants.xml"), StandardCharsets.UTF_8);

        List<com.signalsentinel.core.model.NewsStory> stories = RssNewsCollector.parseStories(atom, "atom-variants");
        assertEquals(3, stories.size());

        assertEquals("Atom Bold News", stories.get(0).title());
        assertEquals("(untitled)", stories.get(1).title());
        assertEquals(Instant.EPOCH, stories.get(2).publishedAt());
        assertTrue(stories.stream().allMatch(story -> story.source().equals("atom-variants")));
    }

    @Test
    void invalidXmlRaisesCollectorAlertAndOtherFeedsStillProcess() throws Exception {
        String goodRss = Files.readString(FixtureUtils.fixturePath("fixtures/sample-rss.xml"), StandardCharsets.UTF_8);
        String badXml = "<rss><channel><item><title>broken";

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/good", exchange -> writeResponse(exchange, goodRss));
        server.createContext("/bad", exchange -> writeResponse(exchange, badXml));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                2,
                List.of(),
                List.of(
                        new RssSourceConfig("good-source", "http://localhost:" + server.getAddress().getPort() + "/good"),
                        new RssSourceConfig("bad-source", "http://localhost:" + server.getAddress().getPort() + "/bad")
                )
        );

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
                Map.of(RssNewsCollector.CONFIG_KEY, cfg)
        );

        new RssNewsCollector().poll(ctx).join();

        assertTrue(store.getNews("good-source").isPresent());
        assertTrue(store.getNews("bad-source").isEmpty());
        assertEquals(1, capture.byType(NewsUpdated.class).size());

        List<AlertRaised> alerts = capture.byType(AlertRaised.class);
        assertEquals(1, alerts.size());
        assertEquals("collector", alerts.getFirst().category());
        assertTrue(alerts.getFirst().message().toLowerCase(java.util.Locale.ROOT).contains("invalid rss"));
    }

    private static void writeResponse(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
