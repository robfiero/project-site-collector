package com.signalsentinel.service.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogDefaultsTest {
    @Test
    void buildsDefaultsFromCatalog() {
        Map<String, Object> defaults = CatalogDefaults.defaults();
        assertTrue(defaults.containsKey("defaultZipCodes"));
        assertTrue(defaults.containsKey("defaultNewsSources"));
        assertTrue(defaults.containsKey("defaultSelectedNewsSources"));
        assertTrue(defaults.containsKey("defaultWatchlist"));
        List<?> sources = (List<?>) defaults.get("defaultNewsSources");
        assertTrue(sources.size() >= 10);
    }
}
