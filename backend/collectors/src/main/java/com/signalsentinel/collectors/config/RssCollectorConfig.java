package com.signalsentinel.collectors.config;

import java.time.Duration;
import java.util.List;

public record RssCollectorConfig(
        Duration interval,
        int topStories,
        List<String> keywords,
        List<RssSourceConfig> sources
) {
}
