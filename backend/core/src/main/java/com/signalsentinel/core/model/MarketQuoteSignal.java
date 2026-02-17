package com.signalsentinel.core.model;

import java.time.Instant;

public record MarketQuoteSignal(
        String symbol,
        double price,
        double change,
        Instant updatedAt
) {
}
