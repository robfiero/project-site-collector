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
        return read(configDir.resolve("collectors.json"), new TypeReference<>() {
        });
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
