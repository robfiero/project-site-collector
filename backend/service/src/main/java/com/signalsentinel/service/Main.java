package com.signalsentinel.service;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
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
import com.signalsentinel.service.runtime.SchedulerService;
import com.signalsentinel.service.store.EventCodec;
import com.signalsentinel.service.store.JsonFileSignalStore;
import com.signalsentinel.service.store.JsonlEventStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        Path dataDir = Path.of("data");
        RuntimeFlags runtimeFlags = resolveRuntimeFlags(System.getenv(), LOGGER::warning);
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

        SiteCollector siteCollector = new SiteCollector(intervalFor(collectorConfigByName, "siteCollector", siteConfig.interval()));
        RssNewsCollector rssCollector = new RssNewsCollector(intervalFor(collectorConfigByName, "rssCollector", rssConfig.interval()));

        Map<String, Object> collectorContextConfig = Map.of(
                SiteCollector.CONFIG_KEY, siteConfig,
                RssNewsCollector.CONFIG_KEY, rssConfig
        );

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
        scheduledCollectors.add(new SchedulerService.ScheduledCollector(
                siteCollector,
                siteCollector.interval(),
                isEnabled(collectorConfigByName, "siteCollector", true)
        ));
        scheduledCollectors.add(new SchedulerService.ScheduledCollector(
                rssCollector,
                rssCollector.interval(),
                isEnabled(collectorConfigByName, "rssCollector", true)
        ));

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

        Map<String, Object> configView = Map.of(
                "collectors", collectorConfigs,
                "sites", siteConfig,
                "rss", rssConfig
        );
        Map<String, Object> catalogDefaults = CatalogDefaults.fromRssConfig(rssConfig);
        List<String> defaultZips = castToStringList(catalogDefaults.get("defaultZipCodes"));
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
        EnvCollector envCollector = new EnvCollector(
                envService,
                () -> authEnabled ? mergedZips(defaultZips, preferencesStore.all()) : defaultZips,
                intervalFor(collectorConfigByName, "envCollector", Duration.ofSeconds(300))
        );
        scheduledCollectors.add(new SchedulerService.ScheduledCollector(
                envCollector,
                envCollector.interval(),
                isEnabled(collectorConfigByName, "envCollector", true)
        ));

        List<Collector> collectors = List.of(siteCollector, rssCollector, envCollector);
        ApiServer apiServer = new ApiServer(
                8080,
                signalStore,
                eventStore,
                broadcaster,
                collectors,
                diagnosticsTracker,
                catalogDefaults,
                configView,
                authService,
                envService,
                !devMode,
                devOutboxEnabled,
                devOutbox
        );

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

    static PasswordHasher selectPasswordHasher(boolean devMode, boolean allowInsecureAuthHasher) {
        PasswordHasher argon2 = PasswordHasher.defaultHasher();
        return selectPasswordHasher(argon2, devMode, allowInsecureAuthHasher);
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
}
