package com.signalsentinel.service.api;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.core.events.Event;
import com.signalsentinel.core.util.JsonUtils;
import com.signalsentinel.service.store.EventStore;
import com.signalsentinel.service.store.ServiceSignalStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

public class ApiServer {
    private static final DiagnosticsTracker EMPTY_DIAGNOSTICS = DiagnosticsTracker.empty();

    private final int port;
    private final ServiceSignalStore signalStore;
    private final EventStore eventStore;
    private final SseBroadcaster sseBroadcaster;
    private final List<Collector> collectors;
    private final DiagnosticsTracker diagnosticsTracker;
    private final Map<String, Object> catalogDefaults;
    private final Map<String, Object> configView;

    private HttpServer server;

    public ApiServer(
            int port,
            ServiceSignalStore signalStore,
            EventStore eventStore,
            SseBroadcaster sseBroadcaster,
            List<Collector> collectors
    ) {
        this(
                port,
                signalStore,
                eventStore,
                sseBroadcaster,
                collectors,
                null,
                Map.of(),
                Map.of()
        );
    }

    public ApiServer(
            int port,
            ServiceSignalStore signalStore,
            EventStore eventStore,
            SseBroadcaster sseBroadcaster,
            List<Collector> collectors,
            DiagnosticsTracker diagnosticsTracker,
            Map<String, Object> catalogDefaults,
            Map<String, Object> configView
    ) {
        this.port = port;
        this.signalStore = signalStore;
        this.eventStore = eventStore;
        this.sseBroadcaster = sseBroadcaster;
        this.collectors = collectors;
        this.diagnosticsTracker = diagnosticsTracker;
        this.catalogDefaults = catalogDefaults;
        this.configView = configView;
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/api/health", this::handleHealth);
            server.createContext("/api/signals", this::handleSignals);
            server.createContext("/api/events", this::handleEvents);
            server.createContext("/api/collectors", this::handleCollectors);
            server.createContext("/api/collectors/status", this::handleCollectorStatus);
            server.createContext("/api/metrics", this::handleMetrics);
            server.createContext("/api/catalog/defaults", this::handleCatalogDefaults);
            server.createContext("/api/config", this::handleConfig);
            server.createContext("/api/stream", sseBroadcaster::handle);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed starting API server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public int actualPort() {
        if (server == null) {
            return port;
        }
        return server.getAddress().getPort();
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        writeJson(exchange, 200, Map.of("status", "ok"));
    }

    private void handleSignals(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        writeJson(exchange, 200, signalStore.getAllSignals());
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }

        Instant since;
        Optional<String> type;
        int limit;
        try {
            Map<String, String> query = queryParams(exchange.getRequestURI());
            since = query.containsKey("since") ? Instant.parse(query.get("since")) : Instant.EPOCH;
            type = Optional.ofNullable(query.get("type")).filter(value -> !value.isBlank());
            limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 200;
        } catch (RuntimeException invalidParamError) {
            writeJson(exchange, 400, Map.of("error", "invalid_query_params"));
            return;
        }

        List<Event> events = eventStore.query(since, type, Math.max(1, limit));
        writeJson(exchange, 200, events);
    }

    private void handleCollectors(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        List<Map<String, Object>> dto = new ArrayList<>();
        for (Collector collector : collectors) {
            dto.add(Map.of(
                    "name", collector.name(),
                    "intervalSeconds", collector.interval().toSeconds()
            ));
        }
        writeJson(exchange, 200, dto);
    }

    private void handleCollectorStatus(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        writeJson(exchange, 200, diagnostics().collectorsSnapshot());
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        writeJson(exchange, 200, diagnostics().metricsSnapshot());
    }

    private void handleCatalogDefaults(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        writeJson(exchange, 200, catalogDefaults);
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        writeJson(exchange, 200, configView);
    }

    private boolean ensureGet(HttpExchange exchange, boolean corsEnabled) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (corsEnabled) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return false;
        }
        return true;
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = JsonUtils.objectMapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private Map<String, String> queryParams(URI uri) {
        Map<String, String> query = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return query;
        }
        for (String entry : raw.split("&")) {
            String[] pair = entry.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            query.put(key, value);
        }
        return query;
    }

    private DiagnosticsTracker diagnostics() {
        return diagnosticsTracker != null ? diagnosticsTracker : EMPTY_DIAGNOSTICS;
    }
}
