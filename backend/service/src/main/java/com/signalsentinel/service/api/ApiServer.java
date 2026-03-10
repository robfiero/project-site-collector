package com.signalsentinel.service.api;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.core.events.Event;
import com.signalsentinel.core.model.LocalHappeningsSignal;
import com.signalsentinel.core.model.NewsSignal;
import com.signalsentinel.core.util.JsonUtils;
import com.signalsentinel.service.auth.AuthMiddleware;
import com.signalsentinel.service.auth.AuthService;
import com.signalsentinel.service.auth.AuthUser;
import com.signalsentinel.service.auth.UserPreferences;
import com.signalsentinel.service.email.DevOutboxEmailSender;
import com.signalsentinel.service.env.EnvService;
import com.signalsentinel.service.market.MarketDataService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class ApiServer {
    private static final DiagnosticsTracker EMPTY_DIAGNOSTICS = DiagnosticsTracker.empty();
    private static final Logger LOGGER = Logger.getLogger(ApiServer.class.getName());
    private static final List<String> AUTH_TRANSITION_REFRESH_COLLECTORS = List.of("envCollector", "rssCollector", "localEventsCollector");

    private final int port;
    private final ServiceSignalStore signalStore;
    private final EventStore eventStore;
    private final SseBroadcaster sseBroadcaster;
    private final List<Collector> collectors;
    private final DiagnosticsTracker diagnosticsTracker;
    private final Map<String, Object> catalogDefaults;
    private final Map<String, Object> configView;
    private final AuthService authService;
    private final EnvService envService;
    private final MarketDataService marketDataService;
    private final boolean secureCookies;
    private final boolean devOutboxEnabled;
    private final DevOutboxEmailSender devOutboxEmailSender;
    private volatile Consumer<List<String>> collectorRefreshHook;

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
                Map.of(),
                null,
                null,
                null,
                false,
                false,
                null
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
        this(
                port,
                signalStore,
                eventStore,
                sseBroadcaster,
                collectors,
                diagnosticsTracker,
                catalogDefaults,
                configView,
                null,
                null,
                null,
                false,
                false,
                null
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
            Map<String, Object> configView,
            AuthService authService,
            EnvService envService,
            MarketDataService marketDataService,
            boolean secureCookies,
            boolean devOutboxEnabled,
            DevOutboxEmailSender devOutboxEmailSender
    ) {
        this.port = port;
        this.signalStore = signalStore;
        this.eventStore = eventStore;
        this.sseBroadcaster = sseBroadcaster;
        this.collectors = collectors;
        this.diagnosticsTracker = diagnosticsTracker;
        this.catalogDefaults = catalogDefaults;
        this.configView = configView;
        this.authService = authService;
        this.envService = envService;
        this.marketDataService = marketDataService;
        this.secureCookies = secureCookies;
        this.devOutboxEnabled = devOutboxEnabled;
        this.devOutboxEmailSender = devOutboxEmailSender;
    }

    public ApiServer(
            int port,
            ServiceSignalStore signalStore,
            EventStore eventStore,
            SseBroadcaster sseBroadcaster,
            List<Collector> collectors,
            DiagnosticsTracker diagnosticsTracker,
            Map<String, Object> catalogDefaults,
            Map<String, Object> configView,
            AuthService authService,
            boolean secureCookies,
            boolean devOutboxEnabled,
            DevOutboxEmailSender devOutboxEmailSender
    ) {
        this(
                port,
                signalStore,
                eventStore,
                sseBroadcaster,
                collectors,
                diagnosticsTracker,
                catalogDefaults,
                configView,
                authService,
                null,
                null,
                secureCookies,
                devOutboxEnabled,
                devOutboxEmailSender
        );
    }

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/api/health", this::handleHealth);
            server.createContext("/api/signals", this::handleSignals);
            server.createContext("/api/events", this::handleEvents);
            server.createContext("/api/collectors", this::handleCollectors);
            server.createContext("/api/collectors/refresh", this::handleCollectorRefresh);
            server.createContext("/api/collectors/status", this::handleCollectorStatus);
            server.createContext("/api/metrics", this::handleMetrics);
            server.createContext("/api/catalog/defaults", this::handleCatalogDefaults);
            server.createContext("/api/settings/newsSources", this::handleNewsSourceSettings);
            server.createContext("/api/settings/reset", this::handleSettingsReset);
            server.createContext("/api/config", this::handleConfig);
            server.createContext("/api/env", this::handleEnvironment);
            server.createContext("/api/markets", this::handleMarkets);
            server.createContext("/api/admin/trends", this::handleAdminTrends);
            server.createContext("/api/admin/email/preview", this::handleAdminEmailPreview);
            server.createContext("/api/auth/signup", this::handleSignup);
            server.createContext("/api/auth/login", this::handleLogin);
            server.createContext("/api/auth/logout", this::handleLogout);
            server.createContext("/api/auth/forgot", this::handleForgotPassword);
            server.createContext("/api/auth/reset", this::handleResetPassword);
            server.createContext("/api/me", this::handleMe);
            server.createContext("/api/me/delete", this::handleAccountDelete);
            server.createContext("/api/me/preferences", this::handlePreferences);
            server.createContext("/api/dev/outbox", this::handleDevOutbox);
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

    public void setCollectorRefreshHook(Consumer<List<String>> collectorRefreshHook) {
        this.collectorRefreshHook = collectorRefreshHook;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        writeJson(exchange, 200, Map.of(
                "status", "ok",
                "version", com.signalsentinel.service.VersionInfo.version(),
                "buildTime", com.signalsentinel.service.VersionInfo.buildTime(),
                "gitSha", com.signalsentinel.service.VersionInfo.gitSha()
        ));
    }

    private void handleAccountDelete(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        Optional<AuthUser> user = AuthMiddleware.requireUser(exchange, authService);
        if (user.isEmpty()) {
            writeJson(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }
        boolean deleted = authService.deleteAccount(user.get().id());
        if (!deleted) {
            writeJson(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        writeJson(exchange, 200, Map.of("status", "deleted"));
    }

    private void handleSignals(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        Map<String, Object> snapshot = new HashMap<>(signalStore.getAllSignals());
        Object news = snapshot.get("news");
        if (news instanceof Map<?, ?> newsMap) {
            Set<String> selected = effectiveSelectedNewsSources(exchange);
            Map<String, Object> filtered = new HashMap<>();
            for (Map.Entry<?, ?> entry : newsMap.entrySet()) {
                if (!(entry.getKey() instanceof String sourceId)) {
                    continue;
                }
                if (!selected.isEmpty() && !selected.contains(sourceId)) {
                    continue;
                }
                filtered.put(sourceId, entry.getValue());
            }
            snapshot.put("news", filtered);
        }
        Object localHappenings = snapshot.get("localHappenings");
        if (localHappenings instanceof Map<?, ?> happeningsMap) {
            Set<String> allowedZips = effectiveZipCodes(exchange);
            Map<String, Object> filtered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : happeningsMap.entrySet()) {
                if (!(entry.getKey() instanceof String zip)) {
                    continue;
                }
                if (!allowedZips.isEmpty() && !allowedZips.contains(zip)) {
                    continue;
                }
                filtered.put(zip, entry.getValue());
            }
            snapshot.put("localHappenings", filtered);
        }
        writeJson(exchange, 200, snapshot);
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

    private void handleCollectorRefresh(HttpExchange exchange) throws IOException {
        if (!ensurePost(exchange)) {
            return;
        }
        Consumer<List<String>> hook = collectorRefreshHook;
        if (hook == null) {
            writeJson(exchange, 501, Map.of("error", "collector_refresh_unavailable"));
            return;
        }
        List<String> requestedCollectors = AUTH_TRANSITION_REFRESH_COLLECTORS;
        try {
            Map<String, Object> body = readBody(exchange);
            List<String> incoming = coerceStringList(body.get("collectors"));
            if (!incoming.isEmpty()) {
                requestedCollectors = incoming;
            }
        } catch (RuntimeException ignored) {
            // Keep default collector list when body is missing/invalid.
        }
        hook.accept(requestedCollectors);
        writeJson(exchange, 200, Map.of(
                "status", "ok",
                "collectors", requestedCollectors
        ));
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

    private void handleNewsSourceSettings(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 200, Map.of(
                    "availableSources", availableNewsSources(),
                    "effectiveSelectedSources", new ArrayList<>(effectiveSelectedNewsSources(exchange))
            ));
            return;
        }

        if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        Optional<AuthUser> user = AuthMiddleware.requireUser(exchange, authService);
        if (user.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> body = readBody(exchange);
            List<String> requested = coerceStringList(body.get("selectedSources"));
            if (requested.isEmpty() && body.containsKey("newsSourceIds")) {
                requested = coerceStringList(body.get("newsSourceIds"));
            }
            Set<String> availableIds = availableNewsSourceIds();
            List<String> validated = requested.stream()
                    .map(String::trim)
                    .filter(id -> !id.isBlank())
                    .filter(availableIds::contains)
                    .distinct()
                    .toList();

            UserPreferences existing = authService.getPreferences(user.get().id());
            UserPreferences updated = authService.updatePreferences(
                    user.get().id(),
                    new UserPreferences(
                            existing.userId(),
                            existing.zipCodes(),
                            existing.watchlist(),
                            validated,
                            existing.themeMode(),
                            existing.accent()
                    )
            );
            writeJson(exchange, 200, Map.of(
                    "availableSources", availableNewsSources(),
                    "effectiveSelectedSources", updated.newsSourceIds().isEmpty()
                            ? defaultSelectedNewsSources()
                            : updated.newsSourceIds()
            ));
        } catch (IllegalArgumentException badInput) {
            writeJson(exchange, 400, Map.of("error", badInput.getMessage()));
        }
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        writeJson(exchange, 200, configView);
    }

    private void handleSettingsReset(HttpExchange exchange) throws IOException {
        if (!ensurePost(exchange)) {
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        Optional<AuthUser> user = AuthMiddleware.requireUser(exchange, authService);
        if (user.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> body = readBody(exchange);
            String scope = String.valueOf(body.getOrDefault("scope", ""))
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (!"ui".equals(scope) && !"collectors".equals(scope) && !"all".equals(scope)) {
                writeJson(exchange, 400, Map.of("error", "scope must be one of: ui, collectors, all"));
                return;
            }
            UserPreferences current = authService.getPreferences(user.get().id());
            UserPreferences next = current;
            if ("ui".equals(scope) || "all".equals(scope)) {
                next = authService.updatePreferences(
                        user.get().id(),
                        new UserPreferences(
                                next.userId(),
                                next.zipCodes(),
                                next.watchlist(),
                                next.newsSourceIds(),
                                UserPreferences.DEFAULT_THEME_MODE,
                                UserPreferences.DEFAULT_ACCENT
                        )
                );
            }
            if ("collectors".equals(scope) || "all".equals(scope)) {
                next = authService.updatePreferences(
                        user.get().id(),
                        new UserPreferences(
                                next.userId(),
                                defaultZipCodes(),
                                defaultWatchlist(),
                                defaultSelectedNewsSources(),
                                next.themeMode(),
                                next.accent()
                        )
                );
            }
            writeJson(exchange, 200, Map.of(
                    "scopeApplied", scope,
                    "preferences", next
            ));
        } catch (IllegalArgumentException badInput) {
            writeJson(exchange, 400, Map.of("error", badInput.getMessage()));
        }
    }

    private void handleEnvironment(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        if (envService == null) {
            writeJson(exchange, 501, Map.of("error", "environment_unavailable"));
            return;
        }
        try {
            Map<String, String> query = queryParams(exchange.getRequestURI());
            List<String> zips = parseZipQuery(query.get("zips"));
            writeJson(exchange, 200, envService.getStatuses(zips));
        } catch (IllegalArgumentException badRequest) {
            writeJson(exchange, 400, Map.of("error", badRequest.getMessage()));
        } catch (RuntimeException upstreamFailure) {
            writeJson(exchange, 502, Map.of("error", "environment_lookup_failed"));
        }
    }

    private void handleAdminTrends(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        Optional<AuthUser> user = AuthMiddleware.requireUser(exchange, authService);
        if (user.isEmpty()) {
            return;
        }
        writeJson(exchange, 200, diagnostics().trendsSnapshot());
    }

    private void handleAdminEmailPreview(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        Optional<AuthUser> user = AuthMiddleware.requireUser(exchange, authService);
        if (user.isEmpty()) {
            return;
        }

        Map<String, Object> snapshot = new HashMap<>(signalStore.getAllSignals());
        int siteCount = countMapEntries(snapshot.get("sites"));
        int newsStoryCount = countNewsStories(snapshot.get("news"), effectiveSelectedNewsSources(exchange));
        int localEventsCount = countLocalEventItems(snapshot.get("localHappenings"), effectiveZipCodes(exchange));
        int weatherCount = countMapEntries(snapshot.get("weather"));
        int marketsCount = countMapEntries(snapshot.get("markets"));
        int totalIncluded = siteCount + newsStoryCount + localEventsCount + weatherCount + marketsCount;

        Instant now = Instant.now();
        String subject = "Today's Overview Digest Preview - " + now.toString().substring(0, 10);
        String body = "";
        if (totalIncluded > 0) {
            List<String> lines = List.of(
                    "Digest preview generated at " + now,
                    "Sites tracked: " + siteCount,
                    "News stories included: " + newsStoryCount,
                    "Local happenings included: " + localEventsCount,
                    "Weather entries: " + weatherCount,
                    "Market entries: " + marketsCount
            );
            body = String.join("\n", lines);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", true);
        payload.put("mode", devOutboxEnabled ? "dev_outbox" : "smtp_or_configured");
        payload.put("lastSentAt", "");
        payload.put("lastError", "");
        payload.put("generatedAt", now.toString());
        payload.put("subject", subject);
        payload.put("body", body);
        payload.put("includedCounts", Map.of(
                "sites", siteCount,
                "newsStories", newsStoryCount,
                "localEvents", localEventsCount,
                "weather", weatherCount,
                "markets", marketsCount
        ));
        writeJson(exchange, 200, payload);
    }

    private void handleMarkets(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        if (marketDataService == null) {
            writeJson(exchange, 501, Map.of("error", "markets_unavailable"));
            return;
        }
        try {
            Map<String, String> query = queryParams(exchange.getRequestURI());
            List<String> symbols = parseSymbolQuery(query.get("symbols"));
            if (symbols.isEmpty()) {
                symbols = defaultWatchlist();
            }
            writeJson(exchange, 200, marketDataService.fetch(symbols));
        } catch (IllegalArgumentException badRequest) {
            writeJson(exchange, 400, Map.of("error", badRequest.getMessage()));
        } catch (IllegalStateException upstreamError) {
            writeJson(exchange, 502, Map.of("error", upstreamError.getMessage()));
        }
    }

    private void handleSignup(HttpExchange exchange) throws IOException {
        if (!ensurePost(exchange)) {
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        try {
            Map<String, Object> body = readBody(exchange);
            String email = String.valueOf(body.getOrDefault("email", ""));
            String password = String.valueOf(body.getOrDefault("password", ""));
            AuthService.AuthResult result = authService.signup(email, password);
            exchange.getResponseHeaders().add("Set-Cookie", AuthMiddleware.buildAuthCookie(result.jwt(), secureCookies));
            writeJson(exchange, 200, Map.of("id", result.userId(), "email", result.email()));
        } catch (IllegalArgumentException badInput) {
            writeJson(exchange, 400, Map.of("error", badInput.getMessage()));
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!ensurePost(exchange)) {
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        try {
            Map<String, Object> body = readBody(exchange);
            String email = String.valueOf(body.getOrDefault("email", ""));
            String password = String.valueOf(body.getOrDefault("password", ""));
            AuthService.AuthResult result = authService.login(email, password);
            exchange.getResponseHeaders().add("Set-Cookie", AuthMiddleware.buildAuthCookie(result.jwt(), secureCookies));
            writeJson(exchange, 200, Map.of("id", result.userId(), "email", result.email()));
            triggerAuthTransitionRefresh();
        } catch (IllegalArgumentException badInput) {
            writeJson(exchange, 401, Map.of("error", badInput.getMessage()));
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        if (!ensurePost(exchange)) {
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        exchange.getResponseHeaders().add("Set-Cookie", AuthMiddleware.buildClearCookie(secureCookies));
        writeJson(exchange, 200, Map.of("status", "ok"));
        triggerAuthTransitionRefresh();
    }

    private void handleMe(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        Optional<AuthUser> user = AuthMiddleware.requireUser(exchange, authService);
        if (user.isEmpty()) {
            return;
        }
        writeJson(exchange, 200, Map.of("id", user.get().id(), "email", user.get().email()));
    }

    private void handlePreferences(HttpExchange exchange) throws IOException {
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        Optional<AuthUser> user = AuthMiddleware.requireUser(exchange, authService);
        if (user.isEmpty()) {
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 200, authService.getPreferences(user.get().id()));
            return;
        }
        if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        try {
            UserPreferences incoming = JsonUtils.objectMapper().readValue(exchange.getRequestBody(), UserPreferences.class);
            UserPreferences updated = authService.updatePreferences(user.get().id(), incoming);
            writeJson(exchange, 200, updated);
        } catch (IllegalArgumentException badInput) {
            writeJson(exchange, 400, Map.of("error", badInput.getMessage()));
        }
    }

    private void handleForgotPassword(HttpExchange exchange) throws IOException {
        if (!ensurePost(exchange)) {
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        Map<String, Object> body = readBody(exchange);
        String email = String.valueOf(body.getOrDefault("email", ""));
        authService.requestPasswordReset(email, exchange.getRemoteAddress().getAddress().getHostAddress());
        writeJson(exchange, 200, Map.of("status", "ok"));
    }

    private void handleResetPassword(HttpExchange exchange) throws IOException {
        if (!ensurePost(exchange)) {
            return;
        }
        if (authService == null) {
            writeJson(exchange, 404, Map.of("error", "auth_disabled"));
            return;
        }
        try {
            Map<String, Object> body = readBody(exchange);
            String token = String.valueOf(body.getOrDefault("token", ""));
            String newPassword = String.valueOf(body.getOrDefault("newPassword", ""));
            authService.resetPassword(token, newPassword);
            writeJson(exchange, 200, Map.of("status", "ok"));
        } catch (IllegalArgumentException badInput) {
            writeJson(exchange, 400, Map.of("error", badInput.getMessage()));
        }
    }

    private void handleDevOutbox(HttpExchange exchange) throws IOException {
        if (!ensureGet(exchange, true)) {
            return;
        }
        if (!devOutboxEnabled || devOutboxEmailSender == null) {
            writeJson(exchange, 404, Map.of("error", "dev_outbox_disabled"));
            return;
        }
        writeJson(exchange, 200, devOutboxEmailSender.recent());
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

    private boolean ensurePost(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
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

    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        return JsonUtils.objectMapper().readValue(exchange.getRequestBody(), Map.class);
    }

    private List<String> parseZipQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!value.matches("\\d{5}")) {
                throw new IllegalArgumentException("zips must be comma-separated 5-digit ZIP codes");
            }
            values.add(value);
        }
        return values;
    }

    private List<String> parseSymbolQuery(String raw) {
        return MarketSymbolParser.parse(raw);
    }

    private List<String> defaultWatchlist() {
        Object raw = catalogDefaults.get("defaultWatchlist");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String symbol && !symbol.isBlank()) {
                list.add(symbol.toUpperCase(Locale.ROOT));
            }
        }
        return list;
    }

    private int countMapEntries(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return 0;
    }

    private int countNewsStories(Object value, Set<String> selectedSourceIds) {
        if (!(value instanceof Map<?, ?> newsMap)) {
            return 0;
        }
        int total = 0;
        for (Map.Entry<?, ?> entry : newsMap.entrySet()) {
            if (!(entry.getKey() instanceof String sourceId) || !selectedSourceIds.contains(sourceId)) {
                continue;
            }
            if (entry.getValue() instanceof NewsSignal signal) {
                total += signal.stories().size();
                continue;
            }
            if (entry.getValue() instanceof Map<?, ?> signal) {
                Object stories = signal.get("stories");
                if (stories instanceof List<?> list) {
                    total += list.size();
                }
            }
        }
        return total;
    }

    private int countLocalEventItems(Object value, Set<String> effectiveZips) {
        if (!(value instanceof Map<?, ?> happeningsMap)) {
            return 0;
        }
        int total = 0;
        for (Map.Entry<?, ?> entry : happeningsMap.entrySet()) {
            if (!(entry.getKey() instanceof String zip) || !effectiveZips.contains(zip)) {
                continue;
            }
            if (entry.getValue() instanceof LocalHappeningsSignal signal) {
                total += signal.items().size();
                continue;
            }
            if (entry.getValue() instanceof Map<?, ?> signal) {
                Object items = signal.get("items");
                if (items instanceof List<?> list) {
                    total += list.size();
                }
            }
        }
        return total;
    }

    private DiagnosticsTracker diagnostics() {
        return diagnosticsTracker != null ? diagnosticsTracker : EMPTY_DIAGNOSTICS;
    }

    private void triggerAuthTransitionRefresh() {
        Consumer<List<String>> hook = collectorRefreshHook;
        if (hook == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                hook.accept(AUTH_TRANSITION_REFRESH_COLLECTORS);
            } catch (RuntimeException ex) {
                LOGGER.warning("Auth transition collector refresh failed: " + ex.getMessage());
            }
        });
    }

    private Set<String> effectiveSelectedNewsSources(HttpExchange exchange) {
        List<String> defaults = defaultSelectedNewsSources();
        if (authService == null) {
            return new LinkedHashSet<>(defaults);
        }
        Optional<AuthUser> user = AuthMiddleware.readAuthCookie(exchange).flatMap(authService::userForToken);
        if (user.isEmpty()) {
            return new LinkedHashSet<>(defaults);
        }
        UserPreferences preferences = authService.getPreferences(user.get().id());
        List<String> selected = preferences.newsSourceIds().isEmpty() ? defaults : preferences.newsSourceIds();
        Set<String> available = availableNewsSourceIds();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : selected) {
            if (available.contains(id)) {
                normalized.add(id);
            }
        }
        if (normalized.isEmpty()) {
            normalized.addAll(defaults);
        }
        return normalized;
    }

    private Set<String> effectiveZipCodes(HttpExchange exchange) {
        List<String> defaults = defaultZipCodes();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (authService == null) {
            normalized.addAll(defaults);
            return normalized;
        }
        Optional<AuthUser> user = AuthMiddleware.readAuthCookie(exchange).flatMap(authService::userForToken);
        if (user.isEmpty()) {
            normalized.addAll(defaults);
            return normalized;
        }
        UserPreferences preferences = authService.getPreferences(user.get().id());
        List<String> requested = preferences.zipCodes().isEmpty() ? defaults : preferences.zipCodes();
        for (String zip : requested) {
            if (zip != null && zip.matches("\\d{5}")) {
                normalized.add(zip);
            }
        }
        if (normalized.isEmpty()) {
            normalized.addAll(defaults);
        }
        return normalized;
    }

    private List<Map<String, Object>> availableNewsSources() {
        Object raw = catalogDefaults.get("defaultNewsSources");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private List<String> defaultSelectedNewsSources() {
        Object raw = catalogDefaults.get("defaultSelectedNewsSources");
        if (raw instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return NewsSourceCatalog.defaultSelectedSourceIds();
    }

    private Set<String> availableNewsSourceIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> source : availableNewsSources()) {
            Object id = source.get("id");
            if (id instanceof String value && !value.isBlank()) {
                ids.add(value);
            }
        }
        return ids;
    }

    private List<String> coerceStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private List<String> defaultZipCodes() {
        Object raw = catalogDefaults.get("defaultZipCodes");
        if (raw instanceof List<?> list) {
            List<String> zips = new ArrayList<>();
            for (Object item : list) {
                String zip = String.valueOf(item);
                if (zip.matches("\\d{5}")) {
                    zips.add(zip);
                }
            }
            if (!zips.isEmpty()) {
                return zips;
            }
        }
        return List.of();
    }
}
