package com.signalsentinel.collectors.config;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public record TicketmasterCollectorConfig(
        String apiKey,
        String baseUrl,
        Supplier<List<String>> zipSupplier,
        int radiusMiles,
        List<String> classifications
) {
    public TicketmasterCollectorConfig {
        Objects.requireNonNull(apiKey, "apiKey is required");
        Objects.requireNonNull(baseUrl, "baseUrl is required");
        Objects.requireNonNull(zipSupplier, "zipSupplier is required");
        Objects.requireNonNull(classifications, "classifications is required");
    }
}
