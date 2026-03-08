package com.signalsentinel.collectors.rss;

import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.RssSourceConfig;
import com.signalsentinel.collectors.support.EventCapture;
import com.signalsentinel.collectors.support.FixtureUtils;
import com.signalsentinel.collectors.support.InMemorySignalStore;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
import com.signalsentinel.core.events.NewsItemsIngested;
import com.signalsentinel.core.events.NewsUpdated;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
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
        assertEquals(1, capture.byType(NewsItemsIngested.class).size());
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
    void parsesPodcastStyleRssWhenLinkIsMissing() throws Exception {
        String podcastRss = Files.readString(FixtureUtils.fixturePath("fixtures/npr-podcast-rss.xml"), StandardCharsets.UTF_8);

        List<com.signalsentinel.core.model.NewsStory> stories = RssNewsCollector.parseStories(podcastRss, "npr_news_now");
        assertEquals(2, stories.size());
        assertEquals("Top headlines this hour", stories.get(0).title());
        assertEquals("https://www.npr.org/podcasts/example-episode-1", stories.get(0).link());
        assertEquals("https://chrt.fm/track/example2.mp3", stories.get(1).link());
    }

    @Test
    void parsesCbsRssFixture() throws Exception {
        String cbsRss = Files.readString(FixtureUtils.fixturePath("fixtures/cbs-rss.xml"), StandardCharsets.UTF_8);

        List<com.signalsentinel.core.model.NewsStory> stories = RssNewsCollector.parseStories(cbsRss, "cbs");
        assertEquals(2, stories.size());
        assertEquals("CBS headline one", stories.get(0).title());
        assertEquals("https://www.cbsnews.com/news/story-one/", stories.get(0).link());
        assertTrue(stories.stream().allMatch(story -> story.source().equals("cbs")));
    }

    @Test
    void parsesAbcRssFixture() throws Exception {
        String abcRss = Files.readString(FixtureUtils.fixturePath("fixtures/abc-rss.xml"), StandardCharsets.UTF_8);

        List<com.signalsentinel.core.model.NewsStory> stories = RssNewsCollector.parseStories(abcRss, "abc");
        assertEquals(2, stories.size());
        assertEquals("ABC headline one", stories.get(0).title());
        assertEquals("https://abcnews.go.com/US/story-one", stories.get(0).link());
        assertTrue(stories.stream().allMatch(story -> story.source().equals("abc")));
    }

    @Test
    void invalidXmlRaisesCollectorAlertAndOtherFeedsStillProcess() throws Exception {
        String goodRss = Files.readString(FixtureUtils.fixturePath("fixtures/sample-rss.xml"), StandardCharsets.UTF_8);
        String badXml = "<rss><channel><item><title>broken";

        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
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

        var result = new RssNewsCollector().poll(ctx).join();

        assertTrue(store.getNews("good-source").isPresent());
        assertTrue(store.getNews("bad-source").isEmpty());
        assertTrue(result.success());
        assertEquals("RSS polling partially completed", result.message());
        assertEquals(1L, result.stats().get("successes"));
        assertEquals(1L, result.stats().get("failures"));
        assertEquals(1, capture.byType(NewsUpdated.class).size());
        assertEquals(1, capture.byType(NewsItemsIngested.class).size());

        List<AlertRaised> alerts = capture.byType(AlertRaised.class);
        assertEquals(1, alerts.size());
        assertEquals("collector", alerts.getFirst().category());
        assertTrue(alerts.getFirst().message().toLowerCase(java.util.Locale.ROOT).contains("invalid rss"));
    }

    @Test
    void missingNytApiKeySkipsWithoutFailingCollector() {
        Assumptions.assumeTrue(
                System.getenv().getOrDefault("NYT_API_KEY", "").isBlank(),
                "Skipping missingNytApiKeySkipsWithoutFailingCollector because NYT_API_KEY is set in environment"
        );
        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                5,
                List.of(),
                List.of(
                        new RssSourceConfig("nyt", "https://api.nytimes.com/svc/topstories/v2/home.json"),
                        new RssSourceConfig("nyt_most_popular", "https://api.nytimes.com/svc/mostpopular/v2/viewed/1.json")
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

        var result = new RssNewsCollector().poll(ctx).join();

        assertTrue(result.success());
        assertTrue(store.getNews("nyt").isEmpty());
        assertTrue(store.getNews("nyt_most_popular").isEmpty());
    }

    @Test
    void allSourcesFailReturnsUnsuccessfulResult() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/bad", exchange -> writeResponse(exchange, "<rss><channel><item><title>broken"));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                2,
                List.of(),
                List.of(
                        new RssSourceConfig("bad-one", "http://localhost:" + server.getAddress().getPort() + "/bad"),
                        new RssSourceConfig("bad-two", "http://localhost:" + server.getAddress().getPort() + "/bad")
                )
        );

        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                new InMemorySignalStore(),
                Clock.fixed(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(1),
                Map.of(RssNewsCollector.CONFIG_KEY, cfg)
        );

        var result = new RssNewsCollector().poll(ctx).join();
        assertFalse(result.success());
        assertEquals("RSS polling had failures", result.message());
        assertEquals(0L, result.stats().get("successes"));
        assertEquals(2L, result.stats().get("failures"));
    }

    @Test
    void accessDeniedStatusRaisesAlertAndSkipsStore() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/forbidden", exchange -> writeResponse(exchange, 403, "<html>forbidden</html>", Map.of("Content-Type", "text/html")));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                2,
                List.of(),
                List.of(new RssSourceConfig("forbidden-source", "http://localhost:" + server.getAddress().getPort() + "/forbidden"))
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

        var result = new RssNewsCollector().poll(ctx).join();
        assertFalse(result.success());
        assertEquals(0L, result.stats().get("successes"));
        assertEquals(1L, result.stats().get("failures"));
        assertTrue(store.getNews("forbidden-source").isEmpty());
        assertEquals(0, capture.byType(NewsItemsIngested.class).size());

        List<AlertRaised> alerts = capture.byType(AlertRaised.class);
        assertEquals(1, alerts.size());
        assertEquals("collector", alerts.getFirst().category());
        assertTrue(alerts.getFirst().message().contains("HTTP 403"));
    }

    @Test
    void serverErrorStatusCountsFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/server-error", exchange -> writeResponse(exchange, 500, "{\"error\":\"boom\"}", Map.of("Content-Type", "application/json")));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                2,
                List.of(),
                List.of(new RssSourceConfig("error-source", "http://localhost:" + server.getAddress().getPort() + "/server-error"))
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

        var result = new RssNewsCollector().poll(ctx).join();
        assertFalse(result.success());
        assertEquals(0L, result.stats().get("successes"));
        assertEquals(1L, result.stats().get("failures"));
        assertTrue(store.getNews("error-source").isEmpty());
    }

    @Test
    void missingContentTypeStillParsesIfBodyIsXml() throws Exception {
        String rss = Files.readString(FixtureUtils.fixturePath("fixtures/sample-rss.xml"), StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/rss-no-content-type", exchange -> writeResponse(exchange, 200, rss, Map.of()));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                3,
                List.of(),
                List.of(new RssSourceConfig("rss-no-content-type", "http://localhost:" + server.getAddress().getPort() + "/rss-no-content-type"))
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

        var result = new RssNewsCollector().poll(ctx).join();
        assertTrue(result.success());
        assertTrue(store.getNews("rss-no-content-type").isPresent());
        assertEquals(1, capture.byType(NewsUpdated.class).size());
        assertEquals(1, capture.byType(NewsItemsIngested.class).size());
        assertEquals(0, capture.byType(AlertRaised.class).size());
    }

    @Test
    void contentTypeTextHtmlBodyResultsInDeterministicOutcome() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/html", exchange -> writeResponse(exchange, 200, "<html>not rss</html>", Map.of("Content-Type", "text/html")));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                2,
                List.of(),
                List.of(new RssSourceConfig("html-source", "http://localhost:" + server.getAddress().getPort() + "/html"))
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

        var result = new RssNewsCollector().poll(ctx).join();
        assertTrue(result.success());
        assertTrue(store.getNews("html-source").isPresent());
        assertEquals(0, store.getNews("html-source").orElseThrow().stories().size());
        assertEquals(1, capture.byType(NewsUpdated.class).size());
        assertEquals(1, capture.byType(NewsItemsIngested.class).size());
        assertEquals(0, capture.byType(AlertRaised.class).size());
    }

    @Test
    void emptyBodyIsInvalidXml() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/empty", exchange -> writeResponse(exchange, 200, "", Map.of("Content-Type", "application/rss+xml")));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                2,
                List.of(),
                List.of(new RssSourceConfig("empty-source", "http://localhost:" + server.getAddress().getPort() + "/empty"))
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

        var result = new RssNewsCollector().poll(ctx).join();
        assertFalse(result.success());
        assertTrue(store.getNews("empty-source").isEmpty());
        assertEquals(1, capture.byType(AlertRaised.class).size());
        assertEquals(0, capture.byType(NewsItemsIngested.class).size());
    }

    @Test
    void redirectResponseIsFollowedAndStored() throws Exception {
        String rss = Files.readString(FixtureUtils.fixturePath("fixtures/sample-rss.xml"), StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/redirect", exchange -> writeResponse(exchange, 302, "", Map.of("Location", "/rss")));
        server.createContext("/rss", exchange -> writeResponse(exchange, 200, rss, Map.of("Content-Type", "application/rss+xml")));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                2,
                List.of(),
                List.of(new RssSourceConfig("redirect-source", "http://localhost:" + server.getAddress().getPort() + "/redirect"))
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

        var result = new RssNewsCollector().poll(ctx).join();
        assertTrue(result.success());
        assertTrue(store.getNews("redirect-source").isPresent());
    }

    @Test
    void keywordAlertNotRaisedWhenNoKeywordsMatch() throws Exception {
        String rss = Files.readString(FixtureUtils.fixturePath("fixtures/sample-rss.xml"), StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/rss", exchange -> writeResponse(exchange, 200, rss, Map.of("Content-Type", "application/rss+xml")));
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                3,
                List.of("definitely-not-in-fixture"),
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

        var result = new RssNewsCollector().poll(ctx).join();
        assertTrue(result.success());
        assertEquals(1, capture.byType(NewsUpdated.class).size());
        assertEquals(1, capture.byType(NewsItemsIngested.class).size());
        assertEquals(0, capture.byType(AlertRaised.class).size());
    }

    @Test
    void nyt429WithRetryAfterEntersCooldownAndSkipsImmediateRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/nyt", exchange -> {
            requests.incrementAndGet();
            writeResponse(exchange, 429, "{\"error\":\"rate limited\"}", Map.of("Retry-After", "120"));
        });
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                5,
                List.of(),
                List.of(new RssSourceConfig("nyt", "http://localhost:" + server.getAddress().getPort() + "/nyt"))
        );

        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-02-25T20:00:00Z"));
        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                store,
                clock,
                Duration.ofSeconds(1),
                Map.of(RssNewsCollector.CONFIG_KEY, cfg)
        );

        RssNewsCollector collector = new RssNewsCollector(Duration.ofSeconds(60), key -> "test-nyt-key");
        var first = collector.poll(ctx).join();
        assertFalse(first.success());
        assertEquals(1, requests.get());
        assertEquals(0, capture.byType(NewsItemsIngested.class).size());

        var second = collector.poll(ctx).join();
        assertFalse(second.success());
        assertEquals(1, requests.get(), "collector should not call upstream again during Retry-After cooldown");
        assertEquals(0, capture.byType(NewsItemsIngested.class).size());
    }

    @Test
    void nyt429WithoutRetryAfterUsesExponentialBackoffAndServesCachedSignal() throws Exception {
        String successBody = """
                {"results":[
                    {"title":"Story A","url":"https://example.com/a","published_date":"2026-02-25T20:00:00Z"}
                ]}
                """;
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/nyt", exchange -> {
            if (requests.getAndIncrement() == 0) {
                writeResponse(exchange, 200, successBody, Map.of("Content-Type", "application/json"));
            } else {
                writeResponse(exchange, 429, "{\"error\":\"rate limited\"}", Map.of("Content-Type", "application/json"));
            }
        });
        server.start();

        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(60),
                5,
                List.of(),
                List.of(new RssSourceConfig("nyt", "http://localhost:" + server.getAddress().getPort() + "/nyt"))
        );

        EventBus bus = new EventBus((event, error) -> {
            throw new AssertionError("Unexpected handler error", error);
        });
        EventCapture capture = new EventCapture(bus);
        InMemorySignalStore store = new InMemorySignalStore();
        MutableClock clock = new MutableClock(Instant.parse("2026-02-25T20:00:00Z"));
        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                store,
                clock,
                Duration.ofSeconds(1),
                Map.of(RssNewsCollector.CONFIG_KEY, cfg)
        );

        RssNewsCollector collector = new RssNewsCollector(Duration.ofSeconds(60), key -> "test-nyt-key");
        var first = collector.poll(ctx).join();
        assertTrue(first.success());
        assertEquals(1, requests.get());
        assertTrue(store.getNews("nyt").isPresent());
        assertEquals(1, capture.byType(NewsItemsIngested.class).size());

        var second = collector.poll(ctx).join();
        assertTrue(second.success(), "collector should serve cached NYT signal while rate-limited");
        assertEquals(2, requests.get());
        assertEquals(1, capture.byType(NewsItemsIngested.class).size(), "cached fallback should not publish new ingest events");

        var third = collector.poll(ctx).join();
        assertTrue(third.success());
        assertEquals(2, requests.get(), "collector should skip repeated calls during exponential cooldown");
        assertEquals(1, capture.byType(NewsItemsIngested.class).size());
    }

    private static void writeResponse(HttpExchange exchange, String body) throws IOException {
        writeResponse(exchange, 200, body, Map.of());
    }

    private static void writeResponse(HttpExchange exchange, int status, String body, Map<String, String> headers) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            exchange.getResponseHeaders().add(header.getKey(), header.getValue());
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
