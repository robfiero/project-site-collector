package com.signalsentinel.service.config;

import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
import com.signalsentinel.collectors.config.WeatherCollectorConfig;
import com.signalsentinel.core.model.CollectorConfig;
import com.signalsentinel.core.model.ParseMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @Test
    void loadsAllServiceConfigs() throws Exception {
        Path dir = Files.createTempDirectory("config-loader-");
        Files.writeString(dir.resolve("collectors.json"), """
                [
                  {"name":"siteCollector","enabled":true,"intervalSeconds":15,"params":{}},
                  {"name":"rssCollector","enabled":false,"intervalSeconds":30,"params":{"topStories":5}}
                ]
                """);
        Files.writeString(dir.resolve("sites.json"), """
                {
                  "interval":"PT15S",
                  "sites":[
                    {"id":"s1","url":"https://example.com","tags":["a"],"parseMode":"TITLE"}
                  ]
                }
                """);
        Files.writeString(dir.resolve("rss.json"), """
                {
                  "interval":"PT30S",
                  "topStories":3,
                  "keywords":["storm"],
                  "sources":[{"source":"x","url":"https://example.com/feed.xml"}]
                }
                """);
        Files.writeString(dir.resolve("weather.json"), """
                {"interval":"PT60S","locations":["Boston"]}
                """);

        List<CollectorConfig> collectors = ConfigLoader.loadCollectors(dir);
        SiteCollectorConfig sites = ConfigLoader.loadSites(dir);
        RssCollectorConfig rss = ConfigLoader.loadRss(dir);
        WeatherCollectorConfig weather = ConfigLoader.loadWeather(dir);

        assertEquals(2, collectors.size());
        assertEquals("siteCollector", collectors.getFirst().name());
        assertEquals(15, collectors.getFirst().intervalSeconds());
        assertEquals(ParseMode.TITLE, sites.sites().getFirst().parseMode());
        assertEquals(3, rss.topStories());
        assertEquals("x", rss.sources().getFirst().source());
        assertEquals("Boston", weather.locations().getFirst());
    }

    @Test
    void missingOrInvalidConfigFailsFastWithPathInMessage() throws Exception {
        Path dir = Files.createTempDirectory("config-loader-invalid-");
        Files.writeString(dir.resolve("collectors.json"), "{not-json");

        IllegalStateException invalid = assertThrows(IllegalStateException.class, () -> ConfigLoader.loadCollectors(dir));
        assertTrue(invalid.getMessage().contains("collectors.json"));

        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> ConfigLoader.loadSites(dir));
        assertTrue(missing.getMessage().contains("sites.json"));
    }
}
