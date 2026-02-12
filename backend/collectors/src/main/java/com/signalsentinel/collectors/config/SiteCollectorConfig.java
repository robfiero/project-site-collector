package com.signalsentinel.collectors.config;

import com.signalsentinel.core.model.SiteConfig;

import java.time.Duration;
import java.util.List;

public record SiteCollectorConfig(Duration interval, List<SiteConfig> sites) {
}
