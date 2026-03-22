package com.signalsentinel.service.config;

import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.SiteCollectorConfig;
import com.signalsentinel.collectors.config.WeatherCollectorConfig;
import com.signalsentinel.core.model.ParseMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @Test
    void loadsAllServiceConfigs() throws Exception {
        Path dir = Path.of(".");
        var collectors = ConfigLoader.loadCollectors(dir);
        SiteCollectorConfig sites = ConfigLoader.loadSites(dir);
        RssCollectorConfig rss = ConfigLoader.loadRss(dir);
        WeatherCollectorConfig weather = ConfigLoader.loadWeather(dir);

        assertEquals(4, collectors.size());
        assertEquals("siteCollector", collectors.getFirst().name());
        assertEquals(30, collectors.getFirst().intervalSeconds());
        assertEquals(ParseMode.TITLE, sites.sites().getFirst().parseMode());
        assertEquals(5, rss.topStories());
        assertEquals("nyt", rss.sources().getFirst().source());
        assertEquals("Boston", weather.locations().getFirst());
    }

    @Test
    void missingConfigResourceFailsFast() {
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> ConfigLoader.readResource(
                "/config/missing.json",
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                },
                "missing.json"
        ));
        assertTrue(missing.getMessage().contains("missing.json"));
    }
}
