package com.signalsentinel.collectors;

import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.RssSourceConfig;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
import com.signalsentinel.collectors.config.WeatherCollectorConfig;
import com.signalsentinel.collectors.rss.RssNewsCollector;
import com.signalsentinel.collectors.site.SiteCollector;
import com.signalsentinel.collectors.support.CollectorContractAssertions;
import com.signalsentinel.collectors.support.EventCapture;
import com.signalsentinel.collectors.support.FixtureUtils;
import com.signalsentinel.collectors.support.InMemorySignalStore;
import com.signalsentinel.collectors.support.CollectorInvariantAssertions;
import com.signalsentinel.collectors.weather.MockWeatherProvider;
import com.signalsentinel.collectors.weather.WeatherCollector;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.events.AlertRaised;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectorContractTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void siteCollectorSatisfiesContractOnPartialFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        int port = server.getAddress().getPort();

        server.createContext("/ok", exchange -> writeResponse(exchange, 200, "<html><head><title>ok</title></head></html>"));
        server.createContext("/hang", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeResponse(exchange, 200, "<html><head><title>late</title></head></html>");
        });
        server.start();

        SiteCollector collector = new SiteCollector();
        SiteCollectorConfig cfg = new SiteCollectorConfig(
                Duration.ofSeconds(10),
                List.of(
                        new SiteConfig("ok", "http://localhost:" + port + "/ok", List.of("contract"), ParseMode.TITLE),
                        new SiteConfig("hang", "http://localhost:" + port + "/hang", List.of("contract"), ParseMode.TITLE)
                )
        );

        EventBus bus = silentBus();
        EventCapture capture = new EventCapture(bus);
        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build(),
                bus,
                new InMemorySignalStore(),
                fixedClock(),
                Duration.ofMillis(100),
                Map.of(SiteCollector.CONFIG_KEY, cfg)
        );

        CollectorContractAssertions.assertContract(collector, ctx, capture, Duration.ofSeconds(2), true);
        CollectorInvariantAssertions.assertTickEnvelope(capture, collector.name());
    }

    @Test
    void rssCollectorSatisfiesContractOnPartialFailure() throws Exception {
        String goodRss = Files.readString(FixtureUtils.fixturePath("fixtures/sample-rss.xml"), StandardCharsets.UTF_8);
        String badXml = "<rss><channel><item><title>broken";

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/good", exchange -> writeResponse(exchange, 200, goodRss));
        server.createContext("/bad", exchange -> writeResponse(exchange, 200, badXml));
        server.start();

        RssNewsCollector collector = new RssNewsCollector();
        RssCollectorConfig cfg = new RssCollectorConfig(
                Duration.ofSeconds(10),
                2,
                List.of(),
                List.of(
                        new RssSourceConfig("good", "http://localhost:" + server.getAddress().getPort() + "/good"),
                        new RssSourceConfig("bad", "http://localhost:" + server.getAddress().getPort() + "/bad")
                )
        );

        EventBus bus = silentBus();
        EventCapture capture = new EventCapture(bus);
        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                bus,
                new InMemorySignalStore(),
                fixedClock(),
                Duration.ofSeconds(1),
                Map.of(RssNewsCollector.CONFIG_KEY, cfg)
        );

        CollectorContractAssertions.assertContract(collector, ctx, capture, Duration.ofSeconds(2), true);
        CollectorInvariantAssertions.assertTickEnvelope(capture, collector.name());
        assertTrue(capture.byType(AlertRaised.class).stream().anyMatch(alert -> "collector".equals(alert.category())));
    }

    @Test
    void weatherCollectorSatisfiesContractOnPartialFailure() {
        WeatherCollector collector = new WeatherCollector(
                new MockWeatherProvider(FixtureUtils.fixturePath("fixtures/mock-weather.json"))
        );
        WeatherCollectorConfig cfg = new WeatherCollectorConfig(
                Duration.ofSeconds(10),
                List.of("Boston", "Nowhere")
        );

        EventBus bus = silentBus();
        EventCapture capture = new EventCapture(bus);
        CollectorContext ctx = new CollectorContext(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build(),
                bus,
                new InMemorySignalStore(),
                fixedClock(),
                Duration.ofMillis(200),
                Map.of(WeatherCollector.CONFIG_KEY, cfg)
        );

        CollectorContractAssertions.assertContract(collector, ctx, capture, Duration.ofSeconds(2), true);
        CollectorInvariantAssertions.assertTickEnvelope(capture, collector.name());
    }

    private static EventBus silentBus() {
        return new EventBus((event, error) -> {
            throw new AssertionError("Unexpected event handler error", error);
        });
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-02-09T20:00:00Z"), ZoneOffset.UTC);
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
