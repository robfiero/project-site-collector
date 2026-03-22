package com.signalsentinel.service.market;

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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataServiceTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchParsesYahooQuotePayloadAndPreservesSymbolOrder() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v7/finance/quote", exchange -> {
            Map<String, String> query = queryParams(exchange.getRequestURI());
            assertEquals("MSFT,AAPL", query.get("symbols"));
            writeResponse(exchange, 200, """
                    {"quoteResponse":{"result":[
                      {"symbol":"AAPL","regularMarketPrice":201.5,"regularMarketChange":1.25,"regularMarketTime":1772000000},
                      {"symbol":"MSFT","regularMarketPrice":400.0,"regularMarketChange":-2.0,"regularMarketTime":1772000010}
                    ]}}
                    """);
        });
        server.start();

        MarketDataService service = new MarketDataService(
                HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/v7/finance/quote",
                Duration.ofSeconds(2),
                Clock.fixed(Instant.parse("2026-02-25T18:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(15),
                Duration.ofMinutes(15),
                ZoneId.of("America/New_York")
        );

        MarketDataService.MarketSnapshot result = service.fetch(List.of("MSFT", "AAPL"));
        assertEquals("ok", result.status());
        assertEquals(2, result.items().size());
        assertEquals("MSFT", result.items().get(0).symbol());
        assertEquals("AAPL", result.items().get(1).symbol());
    }

    @Test
    void malformedPayloadThrowsWhenNoCacheAvailable() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v7/finance/quote", exchange -> writeResponse(exchange, 200, "{\"unexpected\":true}"));
        server.start();

        MarketDataService service = new MarketDataService(
                HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/v7/finance/quote",
                Duration.ofSeconds(2),
                Clock.fixed(Instant.parse("2026-02-25T18:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(15),
                Duration.ofMinutes(15),
                ZoneId.of("America/New_York")
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.fetch(List.of("AAPL")));
        assertTrue(error.getMessage().contains("No quote records"));
    }

    @Test
    void authAndRateLimitAndServerStatusesThrowWhenNoCacheAvailable() throws Exception {
        assertStatusThrows(401, "HTTP 401");
        assertStatusThrows(403, "HTTP 403");
        assertStatusThrows(429, "HTTP 429");
        assertStatusThrows(500, "HTTP 500");
    }

    @Test
    void quote401FallsBackToChartEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v7/finance/quote", exchange -> writeResponse(exchange, 401, "{\"error\":\"unauthorized\"}"));
        server.createContext("/v8/finance/chart/AAPL", exchange -> writeResponse(exchange, 200, """
                {"chart":{"result":[{"meta":{
                  "symbol":"AAPL",
                  "regularMarketPrice":212.10,
                  "chartPreviousClose":210.00,
                  "regularMarketTime":1772003000
                }}]}}
                """));
        server.start();

        MarketDataService service = new MarketDataService(
                HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/v7/finance/quote",
                Duration.ofSeconds(2),
                Clock.fixed(Instant.parse("2026-02-25T18:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(15),
                Duration.ofMinutes(15),
                ZoneId.of("America/New_York")
        );

        MarketDataService.MarketSnapshot result = service.fetch(List.of("AAPL"));
        assertEquals("ok", result.status());
        assertEquals(1, result.items().size());
        assertEquals("AAPL", result.items().getFirst().symbol());
        assertEquals(2.10, result.items().getFirst().change(), 0.001);
    }

    @Test
    void quote401FallsBackToChartEndpointForCaretSymbol() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v7/finance/quote", exchange -> writeResponse(exchange, 401, "{\"error\":\"unauthorized\"}"));
        server.createContext("/v8/finance/chart", exchange -> {
            assertTrue(exchange.getRequestURI().getPath().contains("GSPC"));
            writeResponse(exchange, 200, """
                    {"chart":{"result":[{"meta":{
                      "symbol":"^GSPC",
                      "regularMarketPrice":5050.10,
                      "chartPreviousClose":5030.00,
                      "regularMarketTime":1772003000
                    }}]}}
                    """);
        });
        server.start();

        MarketDataService service = new MarketDataService(
                HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/v7/finance/quote",
                Duration.ofSeconds(2),
                Clock.fixed(Instant.parse("2026-02-25T18:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(15),
                Duration.ofMinutes(15),
                ZoneId.of("America/New_York")
        );

        MarketDataService.MarketSnapshot result = service.fetch(List.of("^GSPC"));
        assertEquals("ok", result.status());
        assertEquals(1, result.items().size());
        assertEquals("^GSPC", result.items().getFirst().symbol());
        assertEquals(20.10, result.items().getFirst().change(), 0.001);
    }

    @Test
    void cachedSnapshotServedAsStaleWhenUpstreamFails() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v7/finance/quote", exchange -> {
            if (counter.getAndIncrement() == 0) {
                writeResponse(exchange, 200, """
                        {"quoteResponse":{"result":[
                          {"symbol":"AAPL","regularMarketPrice":210.0,"regularMarketChange":0.5,"regularMarketTime":1772001000}
                        ]}}
                        """);
            } else {
                writeResponse(exchange, 429, "{\"error\":\"rate limited\"}");
            }
        });
        server.start();

        MutableClock clock = new MutableClock(Instant.parse("2026-03-15T15:00:00Z"));
        MarketDataService service = new MarketDataService(
                HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/v7/finance/quote",
                Duration.ofSeconds(2),
                clock,
                Duration.ofSeconds(15),
                Duration.ofMinutes(15),
                ZoneId.of("America/New_York")
        );

        MarketDataService.MarketSnapshot first = service.fetch(List.of("AAPL"));
        assertEquals("ok", first.status());
        clock.advance(Duration.ofMinutes(1));
        MarketDataService.MarketSnapshot second = service.fetch(List.of("AAPL"));
        assertEquals("stale", second.status());
        assertEquals(1, second.items().size());
        assertTrue(second.stale());
        assertTrue(second.error().contains("HTTP 429"));
    }

    @Test
    void marketOpenUsesShortCacheTtl() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v7/finance/quote", exchange -> {
            if (counter.getAndIncrement() == 0) {
                writeResponse(exchange, 200, """
                        {"quoteResponse":{"result":[
                          {"symbol":"AAPL","regularMarketPrice":210.0,"regularMarketChange":0.5,"regularMarketTime":1772001000}
                        ]}}
                        """);
            } else {
                writeResponse(exchange, 500, "{\"error\":\"server\"}");
            }
        });
        server.start();

        MutableClock clock = new MutableClock(Instant.parse("2026-03-16T14:00:00Z"));
        MarketDataService service = new MarketDataService(
                HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/v7/finance/quote",
                Duration.ofSeconds(2),
                clock,
                Duration.ofSeconds(15),
                Duration.ofMinutes(15),
                ZoneId.of("America/New_York")
        );

        MarketDataService.MarketSnapshot first = service.fetch(List.of("AAPL"));
        assertEquals("ok", first.status());
        clock.advance(Duration.ofSeconds(20));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.fetch(List.of("AAPL")));
        assertTrue(error.getMessage().contains("HTTP 500"));
    }

    @Test
    void marketClosedUsesLongerCacheTtl() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v7/finance/quote", exchange -> {
            if (counter.getAndIncrement() == 0) {
                writeResponse(exchange, 200, """
                        {"quoteResponse":{"result":[
                          {"symbol":"AAPL","regularMarketPrice":210.0,"regularMarketChange":0.5,"regularMarketTime":1772001000}
                        ]}}
                        """);
            } else {
                writeResponse(exchange, 500, "{\"error\":\"server\"}");
            }
        });
        server.start();

        MutableClock clock = new MutableClock(Instant.parse("2026-03-15T15:00:00Z"));
        MarketDataService service = new MarketDataService(
                HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/v7/finance/quote",
                Duration.ofSeconds(2),
                clock,
                Duration.ofSeconds(15),
                Duration.ofMinutes(15),
                ZoneId.of("America/New_York")
        );

        MarketDataService.MarketSnapshot first = service.fetch(List.of("AAPL"));
        assertEquals("ok", first.status());
        clock.advance(Duration.ofMinutes(1));
        MarketDataService.MarketSnapshot second = service.fetch(List.of("AAPL"));
        assertEquals("stale", second.status());
        assertTrue(second.stale());
        assertTrue(second.error().contains("HTTP 500"));
    }

    @Test
    void marketOpenWindowRespectsEasternTime() {
        ZoneId eastern = ZoneId.of("America/New_York");
        assertTrue(MarketDataService.isMarketOpen(Instant.parse("2026-03-16T14:00:00Z"), eastern));
        assertTrue(!MarketDataService.isMarketOpen(Instant.parse("2026-03-16T13:00:00Z"), eastern));
        assertTrue(!MarketDataService.isMarketOpen(Instant.parse("2026-03-16T21:30:00Z"), eastern));
        assertTrue(!MarketDataService.isMarketOpen(Instant.parse("2026-03-15T15:00:00Z"), eastern));
    }

    private void assertStatusThrows(int status, String expectedMessagePart) throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v7/finance/quote", exchange -> writeResponse(exchange, status, "{\"error\":\"x\"}"));
        server.start();

        MarketDataService service = new MarketDataService(
                HttpClient.newHttpClient(),
                "http://localhost:" + server.getAddress().getPort() + "/v7/finance/quote",
                Duration.ofSeconds(2),
                Clock.fixed(Instant.parse("2026-02-25T18:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(15),
                Duration.ofMinutes(15),
                ZoneId.of("America/New_York")
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.fetch(List.of("AAPL")));
        assertTrue(error.getMessage().contains(expectedMessagePart));
        server.stop(0);
        server = null;
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> query = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return query;
        }
        for (String token : raw.split("&")) {
            String[] parts = token.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            query.put(key, value);
        }
        return query;
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
