package com.signalsentinel.service;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
import com.signalsentinel.collectors.config.TicketmasterCollectorConfig;
import com.signalsentinel.collectors.events.TicketmasterEventsCollector;
import com.signalsentinel.collectors.rss.RssNewsCollector;
import com.signalsentinel.collectors.site.SiteCollector;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.model.CollectorConfig;
import com.signalsentinel.service.api.ApiServer;
import com.signalsentinel.service.api.CatalogDefaults;
import com.signalsentinel.service.api.DiagnosticsTracker;
import com.signalsentinel.service.api.SseBroadcaster;
import com.signalsentinel.service.auth.AuthService;
import com.signalsentinel.service.auth.JwtService;
import com.signalsentinel.service.auth.PasswordHasher;
import com.signalsentinel.service.auth.PasswordResetStore;
import com.signalsentinel.service.auth.PreferencesStore;
import com.signalsentinel.service.auth.ResetTokenService;
import com.signalsentinel.service.auth.UserStore;
import com.signalsentinel.service.config.ConfigLoader;
import com.signalsentinel.service.email.DevOutboxEmailSender;
import com.signalsentinel.service.email.EmailSender;
import com.signalsentinel.service.email.SmtpEmailSender;
import com.signalsentinel.service.env.AirNowClient;
import com.signalsentinel.service.env.EnvCollector;
import com.signalsentinel.service.env.EnvService;
import com.signalsentinel.service.env.NoaaClient;
import com.signalsentinel.service.env.TigerwebZipResolver;
import com.signalsentinel.service.env.ZipGeoStore;
import com.signalsentinel.service.http.HttpClientFactory;
import com.signalsentinel.service.market.MarketDataService;
import com.signalsentinel.service.runtime.SchedulerService;
import com.signalsentinel.service.store.EventCodec;
import com.signalsentinel.service.store.JsonFileSignalStore;
import com.signalsentinel.service.store.JsonlEventStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.net.http.HttpClient;

public final class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        Path configDir = Path.of("config");
        Path stateFile = Path.of("state/signals.json");
        Path eventLogFile = Path.of("logs/events.jsonl");
        // DATA_DIR controls where user accounts and preferences are persisted across restarts/redeploys.
        Path dataDir = resolveDataDir(System.getenv(), LOGGER);
        RuntimeFlags runtimeFlags = resolveRuntimeFlags(System.getenv(), LOGGER::warning);
        Map<String, String> env = System.getenv();
        boolean devMode = runtimeFlags.devMode();
        boolean authEnabled = runtimeFlags.authEnabled();
        boolean allowInsecureAuthHasher = runtimeFlags.allowInsecureAuthHasher();

        EventBus eventBus = new EventBus();
        JsonFileSignalStore signalStore = new JsonFileSignalStore(stateFile);
        JsonlEventStore eventStore = new JsonlEventStore(eventLogFile);
        EventCodec.subscribeAll(eventBus, eventStore::append);

        SiteCollectorConfig siteConfig = ConfigLoader.loadSites(configDir);
        RssCollectorConfig rssConfig = ConfigLoader.loadRss(configDir);
        List<CollectorConfig> collectorConfigs = ConfigLoader.loadCollectors(configDir);

        Map<String, CollectorConfig> collectorConfigByName = new HashMap<>();
        for (CollectorConfig cfg : collectorConfigs) {
            collectorConfigByName.put(cfg.name(), cfg);
        }
        if (!collectorConfigByName.containsKey("envCollector")) {
            LOGGER.warning("envCollector is not configured. Runtime collector config source is backend/config/collectors.json.");
        }

        Duration siteInterval = intervalFor(collectorConfigByName, "siteCollector", siteConfig.interval());
        Duration rssInterval = intervalFor(collectorConfigByName, "rssCollector", rssConfig.interval());
        Duration localEventsInterval = intervalFor(collectorConfigByName, "localEventsCollector", Duration.ofSeconds(300));
        SiteCollector siteCollector = new SiteCollector(siteInterval);
        RssNewsCollector rssCollector = new RssNewsCollector(rssInterval);
        TicketmasterEventsCollector ticketmasterEventsCollector = new TicketmasterEventsCollector(localEventsInterval);
        logCollectorConfigSummary(collectorConfigs);
        logRssConfigSummary(rssConfig, rssInterval);

        TicketmasterCollectorConfig ticketmasterConfig = buildTicketmasterConfig(System.getenv(), LOGGER::warning);

        Map<String, Object> collectorContextConfig = new HashMap<>();
        collectorContextConfig.put(SiteCollector.CONFIG_KEY, siteConfig);
        collectorContextConfig.put(RssNewsCollector.CONFIG_KEY, rssConfig);

        HttpClient sharedHttpClient = HttpClientFactory.create(Duration.ofSeconds(5));

        CollectorContext context = new CollectorContext(
                sharedHttpClient,
                eventBus,
                signalStore,
                Clock.systemUTC(),
                Duration.ofSeconds(3),
                collectorContextConfig
        );

        List<SchedulerService.ScheduledCollector> scheduledCollectors = new ArrayList<>();
        registerScheduledCollector(scheduledCollectors, collectorConfigByName, "siteCollector", siteCollector, context, true);
        registerScheduledCollector(scheduledCollectors, collectorConfigByName, "rssCollector", rssCollector, context, true);
        if (ticketmasterConfig != null) {
            registerScheduledCollector(
                    scheduledCollectors,
                    collectorConfigByName,
                    "localEventsCollector",
                    ticketmasterEventsCollector,
                    context,
                    true
            );
        }

        SchedulerService scheduler = new SchedulerService(scheduledCollectors, context);
        SseBroadcaster broadcaster = new SseBroadcaster(eventBus);
        DiagnosticsTracker diagnosticsTracker = new DiagnosticsTracker(eventBus, Clock.systemUTC(), broadcaster::clientCount);
        DevOutboxEmailSender devOutbox = null;
        boolean devOutboxEnabled = authEnabled && !"smtp".equalsIgnoreCase(System.getenv().getOrDefault("EMAIL_MODE", "dev"));
        AuthService authService = null;
        PreferencesStore preferencesStore = new PreferencesStore(dataDir.resolve("preferences.json"));
        if (authEnabled) {
            EmailSender emailSender;
            if (devOutboxEnabled) {
                devOutbox = new DevOutboxEmailSender(dataDir.resolve("outbox.json"));
                emailSender = devOutbox;
            } else {
                emailSender = new SmtpEmailSender(
                        System.getenv().getOrDefault("SMTP_HOST", "localhost"),
                        Integer.parseInt(System.getenv().getOrDefault("SMTP_PORT", "587")),
                        System.getenv().getOrDefault("SMTP_USERNAME", ""),
                        System.getenv().getOrDefault("SMTP_PASSWORD", ""),
                        System.getenv().getOrDefault("SMTP_FROM", "noreply@todaysoverview.local")
                );
            }
            PasswordHasher passwordHasher = selectPasswordHasher(devMode, allowInsecureAuthHasher);
            authService = new AuthService(
                    new UserStore(dataDir.resolve("users.json")),
                    preferencesStore,
                    new PasswordResetStore(dataDir.resolve("password_resets.json")),
                    passwordHasher,
                    new JwtService(
                            System.getenv().getOrDefault("JWT_SECRET", "dev-only-secret-change-me"),
                            Clock.systemUTC(),
                            Duration.ofHours(8)
                    ),
                    new ResetTokenService(),
                    emailSender,
                    eventBus,
                    Clock.systemUTC(),
                    System.getenv().getOrDefault("APP_BASE_URL", "http://localhost:5173")
            );
        } else {
            LOGGER.info("AUTH_ENABLED=false; auth endpoints are disabled.");
        }

        List<String> corsOrigins = parseCsv(env.getOrDefault(
                "UI_ALLOWED_ORIGINS",
                "http://localhost:5173,https://deyyrubsvhyt8.cloudfront.net"
        ));
        Set<String> corsAllowedOrigins = new LinkedHashSet<>(corsOrigins);
        boolean corsAllowCredentials = true;
        if (corsAllowedOrigins.isEmpty()) {
            LOGGER.warning("CORS allowed origins list is empty; cross-origin browser requests will be blocked.");
        } else {
            LOGGER.info("CORS allowed origins: " + String.join(", ", corsAllowedOrigins));
        }

        String authCookieSameSite = normalizeSameSite(env.get("AUTH_COOKIE_SAME_SITE"), devMode);
        String authCookieSecureRaw = env.getOrDefault("AUTH_COOKIE_SECURE", "").trim();
        boolean authCookieSecure = authCookieSecureRaw.isBlank() ? !devMode : "true".equalsIgnoreCase(authCookieSecureRaw);
        if ("None".equalsIgnoreCase(authCookieSameSite) && !authCookieSecure) {
            LOGGER.warning("AUTH_COOKIE_SAME_SITE=None requires Secure; forcing auth cookie Secure=true.");
            authCookieSecure = true;
        }
        LOGGER.info("Auth cookie policy: SameSite=" + authCookieSameSite + ", Secure=" + authCookieSecure);

        Map<String, Object> configView = new java.util.LinkedHashMap<>(Map.of(
                "collectors", collectorConfigs,
                "sites", siteConfig,
                "rss", rssConfig,
                "dataDir", dataDir.toString()
        ));
        configView.put("rssCollectorIntervalSeconds", rssInterval.toSeconds());
        configView.put("rssSourceIntervalSeconds", rssConfig.interval().toSeconds());
        configView.put("corsAllowedOrigins", corsAllowedOrigins);
        configView.put("corsAllowCredentials", corsAllowCredentials);
        configView.put("authCookieSameSite", authCookieSameSite);
        configView.put("authCookieSecure", authCookieSecure);
        Map<String, Object> catalogDefaults = CatalogDefaults.defaults();
        List<String> defaultZips = castToStringList(catalogDefaults.get("defaultZipCodes"));
        java.util.function.Supplier<List<String>> effectiveZipSupplier =
                () -> authEnabled ? mergedZips(defaultZips, preferencesStore.all()) : defaultZips;
        if (ticketmasterConfig != null) {
            ticketmasterConfig = new TicketmasterCollectorConfig(
                    ticketmasterConfig.apiKey(),
                    ticketmasterConfig.baseUrl(),
                    effectiveZipSupplier,
                    ticketmasterConfig.radiusMiles(),
                    ticketmasterConfig.classifications()
            );
            collectorContextConfig.put(TicketmasterEventsCollector.CONFIG_KEY, ticketmasterConfig);
        }
        EnvService envService = new EnvService(
                new ZipGeoStore(dataDir.resolve("zip-geo.json")),
                new TigerwebZipResolver(sharedHttpClient, Duration.ofSeconds(6), Clock.systemUTC()),
                new NoaaClient(
                        sharedHttpClient,
                        Duration.ofSeconds(6),
                        Clock.systemUTC(),
                        System.getenv().getOrDefault("NOAA_USER_AGENT", "todays-overview/0.1 (contact: support@example.com)")
                ),
                new AirNowClient(
                        sharedHttpClient,
                        Duration.ofSeconds(6),
                        Clock.systemUTC(),
                        System.getenv().getOrDefault("AIRNOW_API_KEY", "")
                ),
                Clock.systemUTC(),
                defaultZips
        );
        Duration envInterval = intervalFor(collectorConfigByName, "envCollector", Duration.ofSeconds(300));
        EnvCollector envCollector = new EnvCollector(envService, effectiveZipSupplier, envInterval);
        registerScheduledCollector(scheduledCollectors, collectorConfigByName, "envCollector", envCollector, context, true);
        int marketOpenTtlSeconds = Math.max(1, parseIntOrDefault(env.get("MARKET_CACHE_TTL_OPEN_SECONDS"), 15));
        int marketClosedTtlSeconds = Math.max(1, parseIntOrDefault(env.get("MARKET_CACHE_TTL_CLOSED_SECONDS"), 900));
        java.time.ZoneId marketZone = java.time.ZoneId.of("America/New_York");
        configView.put("marketCacheTtlOpenSeconds", marketOpenTtlSeconds);
        configView.put("marketCacheTtlClosedSeconds", marketClosedTtlSeconds);
        configView.put("marketTimezone", marketZone.toString());
        MarketDataService marketDataService = new MarketDataService(
                sharedHttpClient,
                System.getenv().getOrDefault("MARKETS_BASE_URL", "https://query1.finance.yahoo.com/v7/finance/quote"),
                Duration.ofSeconds(5),
                Clock.systemUTC(),
                Duration.ofSeconds(marketOpenTtlSeconds),
                Duration.ofSeconds(marketClosedTtlSeconds),
                marketZone
        );

        List<Collector> collectors = ticketmasterConfig == null
                ? List.of(siteCollector, rssCollector, envCollector)
                : List.of(siteCollector, rssCollector, envCollector, ticketmasterEventsCollector);
        int port = resolvePort(System.getenv(), LOGGER);
        ApiServer apiServer = new ApiServer(
                port,
                signalStore,
                eventStore,
                broadcaster,
                collectors,
                diagnosticsTracker,
                catalogDefaults,
                configView,
                authService,
                envService,
                marketDataService,
                authCookieSecure,
                authCookieSameSite,
                devOutboxEnabled,
                devOutbox,
                corsAllowedOrigins,
                corsAllowCredentials
        );
        apiServer.setCollectorRefreshHook(collectorsToRun -> scheduler.runOnceCollectors(collectorsToRun));

        scheduler.start();
        apiServer.start();

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            apiServer.stop();
            shutdownLatch.countDown();
        }));

        shutdownLatch.await();
    }

    static int resolvePort(Map<String, String> env, Logger logger) {
        String raw = env.getOrDefault("PORT", "8080").trim();
        if (raw.isBlank()) {
            return 8080;
        }
        try {
            int port = Integer.parseInt(raw);
            if (port <= 0 || port > 65535) {
                throw new NumberFormatException("port out of range");
            }
            return port;
        } catch (NumberFormatException ex) {
            logger.warning("Invalid PORT value '" + raw + "'; defaulting to 8080.");
            return 8080;
        }
    }

    static PasswordHasher selectPasswordHasher(boolean devMode, boolean allowInsecureAuthHasher) {
        PasswordHasher argon2 = PasswordHasher.defaultHasher();
        return selectPasswordHasher(argon2, devMode, allowInsecureAuthHasher);
    }

    static Path resolveDataDir(Map<String, String> env, Logger logger) {
        String dataDirRaw = env.get("DATA_DIR");
        String dataDirValue = (dataDirRaw == null || dataDirRaw.isBlank()) ? "data" : dataDirRaw.trim();
        Path dataDir = Path.of(dataDirValue).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create DATA_DIR at " + dataDir, e);
        }
        if (!Files.isDirectory(dataDir)) {
            throw new IllegalStateException("DATA_DIR is not a directory: " + dataDir);
        }
        if (!Files.isWritable(dataDir)) {
            throw new IllegalStateException("DATA_DIR is not writable: " + dataDir);
        }
        logger.info("Using data directory " + dataDir);
        return dataDir;
    }

    static PasswordHasher selectPasswordHasher(PasswordHasher argon2, boolean devMode, boolean allowInsecureAuthHasher) {
        if (argon2.isAvailable()) {
            return argon2;
        }
        if (!devMode) {
            throw new IllegalStateException("Argon2 native RI backend is required in non-dev mode.");
        }
        LOGGER.warning("Argon2 native RI backend is unavailable. Falling back to PBKDF2 for dev mode.");
        if (allowInsecureAuthHasher) {
            LOGGER.warning("ALLOW_INSECURE_AUTH_HASHER is legacy and no longer required for dev startup; using PBKDF2 fallback.");
        }
        return PasswordHasher.portablePbkdf2();
    }

    static RuntimeFlags resolveRuntimeFlags(Map<String, String> env, Consumer<String> warn) {
        String appEnvRaw = env.getOrDefault("APP_ENV", "dev");
        boolean devMode;
        if ("prod".equalsIgnoreCase(appEnvRaw)) {
            devMode = false;
        } else if ("dev".equalsIgnoreCase(appEnvRaw)) {
            devMode = true;
        } else {
            devMode = true;
            warn.accept("Unknown APP_ENV=" + appEnvRaw + ", defaulting to dev");
        }

        String authEnabledRaw = env.getOrDefault("AUTH_ENABLED", "true");
        boolean authEnabled;
        if ("true".equalsIgnoreCase(authEnabledRaw)) {
            authEnabled = true;
        } else if ("false".equalsIgnoreCase(authEnabledRaw)) {
            authEnabled = false;
        } else {
            authEnabled = true;
            warn.accept("Unknown AUTH_ENABLED=" + authEnabledRaw + ", defaulting to true");
        }

        boolean allowInsecureAuthHasher = "true".equalsIgnoreCase(env.getOrDefault("ALLOW_INSECURE_AUTH_HASHER", "false"));
        if (!devMode && allowInsecureAuthHasher) {
            warn.accept("ALLOW_INSECURE_AUTH_HASHER is ignored in prod mode.");
            allowInsecureAuthHasher = false;
        }

        return new RuntimeFlags(devMode, authEnabled, allowInsecureAuthHasher);
    }

    record RuntimeFlags(boolean devMode, boolean authEnabled, boolean allowInsecureAuthHasher) {
    }

    static TicketmasterCollectorConfig buildTicketmasterConfig(Map<String, String> env, Consumer<String> warn) {
        String apiKey = env.getOrDefault("TICKETMASTER_API_KEY", "").trim();
        if (apiKey.isBlank()) {
            warn.accept("TICKETMASTER_API_KEY is missing; localEventsCollector is disabled.");
            return null;
        }

        String baseUrl = env.getOrDefault("TICKETMASTER_BASE_URL", "https://app.ticketmaster.com/discovery/v2").trim();
        if (baseUrl.isBlank()) {
            baseUrl = "https://app.ticketmaster.com/discovery/v2";
        }
        int radius = parseIntOrDefault(env.get("TICKETMASTER_RADIUS_MILES"), 25);
        List<String> classifications = parseCsv(env.getOrDefault("TICKETMASTER_CLASSIFICATIONS", ""));

        return new TicketmasterCollectorConfig(apiKey, baseUrl, List::of, radius, classifications);
    }

    private static Duration intervalFor(Map<String, CollectorConfig> map, String name, Duration fallback) {
        CollectorConfig config = map.get(name);
        if (config == null) {
            return fallback;
        }
        return Duration.ofSeconds(Math.max(1, config.intervalSeconds()));
    }

    private static boolean isEnabled(Map<String, CollectorConfig> map, String name, boolean fallback) {
        CollectorConfig config = map.get(name);
        return config == null ? fallback : config.enabled();
    }

    private static void registerScheduledCollector(
            List<SchedulerService.ScheduledCollector> scheduledCollectors,
            Map<String, CollectorConfig> configByName,
            String collectorName,
            Collector collector,
            CollectorContext context,
            boolean fallbackEnabled
    ) {
        CollectorConfig config = configByName.get(collectorName);
        boolean enabled = config == null ? fallbackEnabled : config.enabled();
        Duration interval = collector.interval();
        if (!enabled) {
            return;
        }
        scheduledCollectors.add(new SchedulerService.ScheduledCollector(collector, interval, true));
    }

    @SuppressWarnings("unchecked")
    private static List<String> castToStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> casted = new ArrayList<>();
            for (Object entry : list) {
                if (entry != null) {
                    casted.add(String.valueOf(entry));
                }
            }
            return casted;
        }
        return List.of("02108", "98101");
    }

    private static List<String> mergedZips(List<String> defaultZips, List<com.signalsentinel.service.auth.UserPreferences> preferences) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        for (String zip : defaultZips) {
            if (zip != null && zip.matches("\\d{5}")) {
                merged.add(zip);
            }
        }
        for (com.signalsentinel.service.auth.UserPreferences preference : preferences) {
            for (String zip : preference.zipCodes()) {
                if (zip != null && zip.matches("\\d{5}")) {
                    merged.add(zip);
                }
            }
        }
        return List.copyOf(merged);
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void logCollectorConfigSummary(List<CollectorConfig> collectors) {
        LOGGER.info("Collector configuration summary (collectors.json):");
        for (CollectorConfig collector : collectors) {
            LOGGER.info("  " + collector.name()
                    + " enabled=" + collector.enabled()
                    + " intervalSeconds=" + collector.intervalSeconds());
        }
    }

    private static void logRssConfigSummary(RssCollectorConfig rssConfig, Duration rssInterval) {
        int sourceCount = rssConfig.sources() == null ? 0 : rssConfig.sources().size();
        int keywordCount = rssConfig.keywords() == null ? 0 : rssConfig.keywords().size();
        LOGGER.info("RSS configuration summary: rss.intervalSeconds=" + rssConfig.interval().toSeconds()
                + " topStories=" + rssConfig.topStories()
                + " sources=" + sourceCount
                + " keywords=" + keywordCount);
        LOGGER.info("Scheduling note: rssCollector cadence is controlled by collectors.json intervalSeconds ("
                + rssInterval.toSeconds() + "s). rss.interval is informational only.");
    }

    private static String normalizeSameSite(String raw, boolean devMode) {
        String fallback = devMode ? "Lax" : "None";
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim();
        if ("Lax".equalsIgnoreCase(normalized)) {
            return "Lax";
        }
        if ("Strict".equalsIgnoreCase(normalized)) {
            return "Strict";
        }
        if ("None".equalsIgnoreCase(normalized)) {
            return "None";
        }
        LOGGER.warning("Unknown AUTH_COOKIE_SAME_SITE=" + raw + "; defaulting to " + fallback);
        return fallback;
    }

    private static List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }
}
