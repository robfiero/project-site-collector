package com.signalsentinel.collectors.config;

import java.time.Duration;
import java.util.List;

public record WeatherCollectorConfig(Duration interval, List<String> locations) {
}
