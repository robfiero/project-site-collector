package com.signalsentinel.service.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
import com.signalsentinel.collectors.config.WeatherCollectorConfig;
import com.signalsentinel.core.model.CollectorConfig;
import com.signalsentinel.core.util.JsonUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public final class ConfigLoader {
    private static final Logger LOGGER = Logger.getLogger(ConfigLoader.class.getName());
    private static final String COLLECTORS_RESOURCE = "/config/collectors.json";
    private static final String SITES_RESOURCE = "/config/sites.json";
    private static final String RSS_RESOURCE = "/config/rss.json";
    private static final String WEATHER_RESOURCE = "/config/weather.json";

    private ConfigLoader() {
    }

    public static List<CollectorConfig> loadCollectors(Path configDir) {
        LOGGER.info("Config source: collectors=classpath:" + COLLECTORS_RESOURCE);
        List<CollectorConfig> collectors = readResource(COLLECTORS_RESOURCE, new TypeReference<>() {
        }, "collectors.json");
        LOGGER.info("Config summary: collectors=" + collectors.size());
        return collectors;
    }

    public static SiteCollectorConfig loadSites(Path configDir) {
        LOGGER.info("Config source: sites=classpath:" + SITES_RESOURCE);
        SiteCollectorConfig sites = readResource(SITES_RESOURCE, new TypeReference<>() {
        }, "sites.json");
        return sites;
    }

    public static RssCollectorConfig loadRss(Path configDir) {
        LOGGER.info("Config source: rss=classpath:" + RSS_RESOURCE);
        RssCollectorConfig rss = readResource(RSS_RESOURCE, new TypeReference<>() {
        }, "rss.json");
        int sourceCount = rss.sources() == null ? 0 : rss.sources().size();
        LOGGER.info("Config summary: rssSources=" + sourceCount);
        return rss;
    }

    public static WeatherCollectorConfig loadWeather(Path configDir) {
        LOGGER.info("Config source: weather=classpath:" + WEATHER_RESOURCE);
        WeatherCollectorConfig weather = readResource(WEATHER_RESOURCE, new TypeReference<>() {
        }, "weather.json");
        return weather;
    }

    static <T> T readResource(String resourcePath, TypeReference<T> ref, String label) {
        InputStream in = ConfigLoader.class.getResourceAsStream(resourcePath);
        if (in == null) {
            String message = "Missing config resource " + resourcePath + " (expected " + label + " on classpath)";
            LOGGER.severe(message);
            throw new IllegalStateException(message);
        }
        try (InputStream stream = in) {
            return JsonUtils.objectMapper().readValue(stream, ref);
        } catch (IOException e) {
            throw new IllegalStateException("Failed loading config from classpath " + resourcePath, e);
        }
    }
}
