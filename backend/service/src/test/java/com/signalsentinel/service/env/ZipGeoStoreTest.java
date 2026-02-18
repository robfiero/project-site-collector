package com.signalsentinel.service.env;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipGeoStoreTest {
    @Test
    void persistsAndReloadsZipCoordinates() throws Exception {
        Path tempDir = Files.createTempDirectory("zip-geo-store-test-");
        Path file = tempDir.resolve("data/zip-geo.json");

        ZipGeoStore first = new ZipGeoStore(file);
        first.put(new ZipGeoRecord("02108", 42.35, -71.06, Instant.parse("2026-02-18T00:00:00Z"), "tigerweb_zcta"));

        ZipGeoStore second = new ZipGeoStore(file);
        ZipGeoRecord record = second.get("02108").orElseThrow();
        assertEquals(42.35, record.lat());
        assertEquals(-71.06, record.lon());
        assertEquals("tigerweb_zcta", record.source());
        assertTrue(Files.exists(file));
    }
}

