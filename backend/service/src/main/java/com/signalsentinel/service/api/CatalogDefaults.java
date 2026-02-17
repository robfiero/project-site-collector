package com.signalsentinel.service.api;

import com.signalsentinel.collectors.config.RssCollectorConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CatalogDefaults {
    private CatalogDefaults() {
    }

    public static Map<String, Object> fromRssConfig(RssCollectorConfig rssConfig) {
        List<Map<String, Object>> sources = rssConfig.sources().stream()
                .map(source -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", source.source());
                    item.put("name", source.source());
                    item.put("url", source.url());
                    item.put("category", "general");
                    return item;
                })
                .toList();

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("defaultZipCodes", List.of("02108", "98101"));
        defaults.put("defaultNewsSources", sources);
        defaults.put("defaultWatchlist", List.of("AAPL", "MSFT", "SPY", "BTC-USD", "ETH-USD"));
        return defaults;
    }
}
