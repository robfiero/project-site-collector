package com.signalsentinel.service.api;

import java.util.List;
import java.util.Map;

public final class CatalogDefaults {
    private CatalogDefaults() {
    }

    public static Map<String, Object> defaults() {
        return Map.of(
                "defaultZipCodes", List.of("02108", "98101"),
                "defaultNewsSources", NewsSourceCatalog.asApiList(),
                "defaultSelectedNewsSources", NewsSourceCatalog.defaultSelectedSourceIds(),
                "defaultWatchlist", List.of("DJIA", "^GSPC", "ORCL", "AAPL", "SBUX", "HD", "DIS", "MSFT", "AMZN", "BTC-USD", "NFLX")
        );
    }
}
