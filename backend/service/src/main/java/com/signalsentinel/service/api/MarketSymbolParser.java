package com.signalsentinel.service.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class MarketSymbolParser {
    private MarketSymbolParser() {
    }

    static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] tokens = raw.split(",");
        if (tokens.length > 100) {
            throw new IllegalArgumentException("Too many symbols requested");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : tokens) {
            String value = token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
            if (value.isBlank()) {
                continue;
            }
            if (!value.matches("[A-Z0-9._\\-\\^]{1,16}")) {
                throw new IllegalArgumentException("Invalid symbol: " + value);
            }
            values.add(value);
        }
        return new ArrayList<>(values);
    }
}
