package com.signalsentinel.service.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
import com.signalsentinel.collectors.config.WeatherCollectorConfig;
import com.signalsentinel.core.model.CollectorConfig;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static List<CollectorConfig> loadCollectors(Path configDir) {
        Path __debugCollectors = configDir.resolve("collectors.json");
        System.out.println("\n=== ENV COLLECTOR DEBUG ===");
        System.out.println("user.dir=" + System.getProperty("user.dir"));
        System.out.println("configDir(raw)=" + configDir);
        System.out.println("configDir(abs)=" + configDir.toAbsolutePath().normalize());
        System.out.println("collectors.json(abs)=" + __debugCollectors.toAbsolutePath().normalize());
        try {
            System.out.println("collectors.json exists=" + java.nio.file.Files.exists(__debugCollectors));
            System.out.println("collectors.json readable=" + java.nio.file.Files.isReadable(__debugCollectors));
        } catch (Exception ignored) {}
        System.out.println("===========================\n");
        Path collectorsPath = configDir.resolve("collectors.json");
        List<CollectorConfig> collectors = read(collectorsPath, new TypeReference<>() {
        });
        System.out.println("Collectors loaded from " + collectorsPath.toAbsolutePath().normalize());
        System.out.println("Collector keys=" + collectors.stream().map(CollectorConfig::name).toList());
        for (CollectorConfig collector : collectors) {
            System.out.println("  " + collector.name() + " enabled=" + collector.enabled() + " interval=" + collector.intervalSeconds());
        }
        return collectors;
    }

    public static SiteCollectorConfig loadSites(Path configDir) {
        return read(configDir.resolve("sites.json"), new TypeReference<>() {
        });
    }

    public static RssCollectorConfig loadRss(Path configDir) {
        return read(configDir.resolve("rss.json"), new TypeReference<>() {
        });
    }

    public static WeatherCollectorConfig loadWeather(Path configDir) {
        return read(configDir.resolve("weather.json"), new TypeReference<>() {
        });
    }

    private static <T> T read(Path path, TypeReference<T> ref) {
        try (InputStream in = Files.newInputStream(path)) {
            return JsonUtils.objectMapper().readValue(in, ref);
        } catch (IOException e) {
            throw new IllegalStateException("Failed loading config from " + path, e);
        }
    }
}
