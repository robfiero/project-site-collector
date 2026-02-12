package com.signalsentinel.collectors.api;

import com.signalsentinel.core.bus.EventBus;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record CollectorContext(
        HttpClient httpClient,
        EventBus eventBus,
        SignalStore signalStore,
        Clock clock,
        Duration requestTimeout,
        Map<String, Object> config
) {
    public CollectorContext {
        Objects.requireNonNull(httpClient, "httpClient is required");
        Objects.requireNonNull(eventBus, "eventBus is required");
        Objects.requireNonNull(signalStore, "signalStore is required");
        Objects.requireNonNull(clock, "clock is required");
        Objects.requireNonNull(requestTimeout, "requestTimeout is required");
        Objects.requireNonNull(config, "config is required");
    }

    public <T> T requiredConfig(String key, Class<T> type) {
        Object value = config.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Config key '" + key + "' must be " + type.getSimpleName());
        }
        return type.cast(value);
    }
}
