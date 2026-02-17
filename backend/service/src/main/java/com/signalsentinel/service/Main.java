package com.signalsentinel.service;

import com.signalsentinel.collectors.api.Collector;
import com.signalsentinel.collectors.api.CollectorContext;
import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
import com.signalsentinel.collectors.config.WeatherCollectorConfig;
import com.signalsentinel.collectors.rss.RssNewsCollector;
import com.signalsentinel.collectors.site.SiteCollector;
import com.signalsentinel.collectors.weather.MockWeatherProvider;
import com.signalsentinel.collectors.weather.WeatherCollector;
import com.signalsentinel.core.bus.EventBus;
import com.signalsentinel.core.model.CollectorConfig;
import com.signalsentinel.service.api.ApiServer;
import com.signalsentinel.service.api.CatalogDefaults;
import com.signalsentinel.service.api.DiagnosticsTracker;
import com.signalsentinel.service.api.SseBroadcaster;
import com.signalsentinel.service.config.ConfigLoader;
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

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        Path configDir = Path.of("config");
        Path stateFile = Path.of("state/signals.json");
        Path eventLogFile = Path.of("logs/events.jsonl");

        EventBus eventBus = new EventBus();
        JsonFileSignalStore signalStore = new JsonFileSignalStore(stateFile);
        JsonlEventStore eventStore = new JsonlEventStore(eventLogFile);
        EventCodec.subscribeAll(eventBus, eventStore::append);

        SiteCollectorConfig siteConfig = ConfigLoader.loadSites(configDir);
        RssCollectorConfig rssConfig = ConfigLoader.loadRss(configDir);
        WeatherCollectorConfig weatherConfig = ConfigLoader.loadWeather(configDir);
        List<CollectorConfig> collectorConfigs = ConfigLoader.loadCollectors(configDir);

        Map<String, CollectorConfig> collectorConfigByName = new HashMap<>();
        for (CollectorConfig cfg : collectorConfigs) {
            collectorConfigByName.put(cfg.name(), cfg);
        }

        SiteCollector siteCollector = new SiteCollector(intervalFor(collectorConfigByName, "siteCollector", siteConfig.interval()));
        RssNewsCollector rssCollector = new RssNewsCollector(intervalFor(collectorConfigByName, "rssCollector", rssConfig.interval()));
        WeatherCollector weatherCollector = new WeatherCollector(
                new MockWeatherProvider(configDir.resolve("mock-weather.json")),
                intervalFor(collectorConfigByName, "weatherCollector", weatherConfig.interval())
        );

        Map<String, Object> collectorContextConfig = Map.of(
                SiteCollector.CONFIG_KEY, siteConfig,
                RssNewsCollector.CONFIG_KEY, rssConfig,
                WeatherCollector.CONFIG_KEY, weatherConfig
        );

        CollectorContext context = new CollectorContext(
                HttpClientFactory.create(Duration.ofSeconds(5)),
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
        scheduledCollectors.add(new SchedulerService.ScheduledCollector(
                weatherCollector,
                weatherCollector.interval(),
                isEnabled(collectorConfigByName, "weatherCollector", true)
        ));

        SchedulerService scheduler = new SchedulerService(scheduledCollectors, context);
        SseBroadcaster broadcaster = new SseBroadcaster(eventBus);
        DiagnosticsTracker diagnosticsTracker = new DiagnosticsTracker(eventBus, Clock.systemUTC(), broadcaster::clientCount);
        Map<String, Object> configView = Map.of(
                "collectors", collectorConfigs,
                "sites", siteConfig,
                "rss", rssConfig,
                "weather", weatherConfig
        );
        Map<String, Object> catalogDefaults = CatalogDefaults.fromRssConfig(rssConfig);
        List<Collector> collectors = List.of(siteCollector, rssCollector, weatherCollector);
        ApiServer apiServer = new ApiServer(
                8080,
                signalStore,
                eventStore,
                broadcaster,
                collectors,
                diagnosticsTracker,
                catalogDefaults,
                configView
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
}
