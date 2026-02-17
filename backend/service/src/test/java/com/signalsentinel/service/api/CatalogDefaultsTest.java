package com.signalsentinel.service.api;

import com.signalsentinel.collectors.config.RssCollectorConfig;
import com.signalsentinel.collectors.config.RssSourceConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogDefaultsTest {
    @Test
    void buildsDefaultsFromRssConfig() {
        RssCollectorConfig config = new RssCollectorConfig(
                Duration.ofMinutes(1),
                5,
                List.of("storm"),
                List.of(new RssSourceConfig("bbc", "https://feeds.bbci.co.uk/news/rss.xml"))
        );

        Map<String, Object> defaults = CatalogDefaults.fromRssConfig(config);
        assertTrue(defaults.containsKey("defaultZipCodes"));
        assertTrue(defaults.containsKey("defaultNewsSources"));
        assertTrue(defaults.containsKey("defaultWatchlist"));
        List<?> sources = (List<?>) defaults.get("defaultNewsSources");
        assertEquals(1, sources.size());
    }
}
