package com.signalsentinel.service.market;

import com.signalsentinel.core.model.MarketQuoteSignal;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class MarketDataService {
    private static final Logger LOGGER = Logger.getLogger(MarketDataService.class.getName());

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration timeout;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Map<String, CachedSnapshot> cacheBySymbolKey = new HashMap<>();
    private final Map<String, String> lastFailureBySymbolKey = new HashMap<>();

    public MarketDataService(HttpClient httpClient, String baseUrl, Duration timeout, Clock clock, Duration cacheTtl) {
        this.httpClient = httpClient;
        this.endpoint = URI.create(baseUrl);
        this.timeout = timeout;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    public synchronized MarketSnapshot fetch(List<String> symbols) {
        List<String> normalized = normalizeSymbols(symbols);
        Instant now = clock.instant();
        if (normalized.isEmpty()) {
            return new MarketSnapshot("ok", now, List.of(), null, false);
        }

        String symbolKey = String.join(",", normalized);
        URI requestUri = buildUri(normalized);
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .GET()
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "SignalSentinel/0.1")
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                List<MarketQuoteSignal> fallback = fetchViaChartFallback(normalized);
                if (!fallback.isEmpty()) {
                    MarketSnapshot snapshot = new MarketSnapshot("ok", now, fallback, null, false);
                    cacheBySymbolKey.put(symbolKey, new CachedSnapshot(snapshot, now.plus(cacheTtl)));
                    lastFailureBySymbolKey.remove(symbolKey);
                    LOGGER.info(() -> "Markets upstream status=" + status + " quoteFallback=chart records=" + fallback.size() + " symbols=" + symbolKey);
                    return snapshot;
                }
            }
            if (status < 200 || status >= 300) {
                return cachedOrThrow(symbolKey, "HTTP " + status + " from markets upstream");
            }

            List<MarketQuoteSignal> parsed = parseQuotes(response.body(), normalized);
            if (parsed.isEmpty()) {
                return cachedOrThrow(symbolKey, "No quote records returned by markets upstream");
            }

            MarketSnapshot snapshot = new MarketSnapshot("ok", now, parsed, null, false);
            cacheBySymbolKey.put(symbolKey, new CachedSnapshot(snapshot, now.plus(cacheTtl)));
            lastFailureBySymbolKey.remove(symbolKey);
            LOGGER.info(() -> "Markets upstream status=200 records=" + parsed.size() + " symbols=" + symbolKey);
            return snapshot;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return cachedOrThrow(symbolKey, "Markets upstream request failed: " + rootMessage(e));
        }
    }

    private List<MarketQuoteSignal> fetchViaChartFallback(List<String> symbols) {
        List<MarketQuoteSignal> items = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                HttpRequest request = HttpRequest.newBuilder(buildChartUri(symbol))
                        .GET()
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .header("User-Agent", "SignalSentinel/0.1")
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    continue;
                }
                parseChartQuote(response.body(), symbol).ifPresent(items::add);
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return items;
    }

    private MarketSnapshot cachedOrThrow(String symbolKey, String reason) {
        Instant now = clock.instant();
        CachedSnapshot cached = cacheBySymbolKey.get(symbolKey);
        if (cached != null && !cached.expiresAt().isBefore(now)) {
            logFailureThrottled(symbolKey, reason + "; serving cached data");
            MarketSnapshot value = cached.snapshot();
            return new MarketSnapshot("stale", value.asOf(), value.items(), reason, true);
        }
        logFailureThrottled(symbolKey, reason);
        throw new IllegalStateException(reason);
    }

    private void logFailureThrottled(String symbolKey, String reason) {
        String previous = lastFailureBySymbolKey.get(symbolKey);
        if (reason.equals(previous)) {
            return;
        }
        lastFailureBySymbolKey.put(symbolKey, reason);
        LOGGER.warning("Markets upstream failure symbols=" + symbolKey + " reason=" + reason);
    }

    private URI buildUri(List<String> symbols) {
        String csv = String.join(",", symbols);
        String encoded = URLEncoder.encode(csv, StandardCharsets.UTF_8);
        String separator = endpoint.toString().contains("?") ? "&" : "?";
        return URI.create(endpoint + separator + "symbols=" + encoded);
    }

    private URI buildChartUri(String symbol) {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        String base = endpoint.toString();
        if (base.contains("/v7/finance/quote")) {
            return URI.create(base.replace("/v7/finance/quote", "/v8/finance/chart/" + encodedSymbol) + "?interval=1m&range=1d");
        }
        URI root = URI.create(endpoint.getScheme() + "://" + endpoint.getAuthority());
        return root.resolve("/v8/finance/chart/" + encodedSymbol + "?interval=1m&range=1d");
    }

    private List<MarketQuoteSignal> parseQuotes(String body, List<String> requestOrder) {
        try {
            var root = JsonUtils.objectMapper().readTree(body);
            var results = root.path("quoteResponse").path("result");
            if (!results.isArray()) {
                return List.of();
            }
            Map<String, Integer> order = new HashMap<>();
            for (int i = 0; i < requestOrder.size(); i++) {
                order.put(requestOrder.get(i), i);
            }

            List<MarketQuoteSignal> values = new ArrayList<>();
            for (var node : results) {
                String symbol = node.path("symbol").asText("").trim().toUpperCase(Locale.ROOT);
                if (symbol.isBlank()) {
                    continue;
                }
                if (!node.has("regularMarketPrice")) {
                    continue;
                }
                double price = node.path("regularMarketPrice").asDouble(Double.NaN);
                if (!Double.isFinite(price)) {
                    continue;
                }
                double change = node.path("regularMarketChange").asDouble(0.0);
                long epochSeconds = node.path("regularMarketTime").asLong(0L);
                Instant updatedAt = epochSeconds > 0 ? Instant.ofEpochSecond(epochSeconds) : clock.instant();
                values.add(new MarketQuoteSignal(symbol, price, change, updatedAt));
            }
            values.sort(Comparator.comparingInt(v -> order.getOrDefault(v.symbol(), Integer.MAX_VALUE)));
            return values;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private java.util.Optional<MarketQuoteSignal> parseChartQuote(String body, String requestedSymbol) {
        try {
            var root = JsonUtils.objectMapper().readTree(body);
            var result = root.path("chart").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return java.util.Optional.empty();
            }
            var first = result.get(0);
            var meta = first.path("meta");
            String symbol = meta.path("symbol").asText(requestedSymbol).trim().toUpperCase(Locale.ROOT);
            if (symbol.isBlank()) {
                symbol = requestedSymbol.toUpperCase(Locale.ROOT);
            }
            double price = meta.path("regularMarketPrice").asDouble(Double.NaN);
            if (!Double.isFinite(price)) {
                return java.util.Optional.empty();
            }
            double previousClose = meta.path("chartPreviousClose").asDouble(Double.NaN);
            if (!Double.isFinite(previousClose)) {
                previousClose = meta.path("previousClose").asDouble(Double.NaN);
            }
            double change = Double.isFinite(previousClose) ? price - previousClose : 0.0;
            long epochSeconds = meta.path("regularMarketTime").asLong(0L);
            Instant updatedAt = epochSeconds > 0 ? Instant.ofEpochSecond(epochSeconds) : clock.instant();
            return java.util.Optional.of(new MarketQuoteSignal(symbol, price, change, updatedAt));
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private static List<String> normalizeSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol == null) {
                continue;
            }
            String normalized = symbol.trim().toUpperCase(Locale.ROOT);
            if (normalized.isBlank()) {
                continue;
            }
            if (!values.contains(normalized)) {
                values.add(normalized);
            }
        }
        return values;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record CachedSnapshot(MarketSnapshot snapshot, Instant expiresAt) {
    }

    public record MarketSnapshot(String status, Instant asOf, List<MarketQuoteSignal> items, String error, boolean stale) {
    }
}
