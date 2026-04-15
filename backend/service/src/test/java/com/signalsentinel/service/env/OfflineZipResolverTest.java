package com.signalsentinel.service.env;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfflineZipResolverTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-02-12T20:00:00Z"), ZoneOffset.UTC);

    @Test
    void resolvesKnownZipFromOfflineDataset() {
        OfflineZipResolver resolver = new OfflineZipResolver(unreachableFallback(), FIXED_CLOCK);

        ZipGeoRecord record = resolver.resolve("03251");

        assertEquals("03251", record.zip());
        assertEquals("Lincoln", record.city());
        assertEquals("NH", record.state());
        assertNotNull(record.lat());
        assertNotNull(record.lon());
        assertEquals("offline_geonames", record.source());
    }

    @Test
    void resolvesAnotherKnownZipWithCorrectCityAndState() {
        OfflineZipResolver resolver = new OfflineZipResolver(unreachableFallback(), FIXED_CLOCK);

        ZipGeoRecord record = resolver.resolve("10001");

        assertEquals("10001", record.zip());
        assertEquals("NY", record.state());
        assertNotNull(record.city());
    }

    @Test
    void fallsBackToTigerwebForUnknownZip() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ZipGeoResolver fallback = zip -> {
            fallbackCalls.incrementAndGet();
            return new ZipGeoRecord(zip, 40.0, -75.0, Instant.now(FIXED_CLOCK), "tigerweb_zcta");
        };
        OfflineZipResolver resolver = new OfflineZipResolver(fallback, FIXED_CLOCK);

        // Use a ZIP that is extremely unlikely to be in the dataset (all zeros)
        ZipGeoRecord record = resolver.resolve("00000");

        assertEquals(1, fallbackCalls.get(), "Fallback should have been called once");
        assertEquals("00000", record.zip());
    }

    @Test
    void rejectsInvalidZipFormats() {
        OfflineZipResolver resolver = new OfflineZipResolver(unreachableFallback(), FIXED_CLOCK);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("1234"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("ABCDE"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(""));
    }

    @Test
    void offlineRecordTimestampMatchesClock() {
        OfflineZipResolver resolver = new OfflineZipResolver(unreachableFallback(), FIXED_CLOCK);

        ZipGeoRecord record = resolver.resolve("90210");

        assertEquals(Instant.parse("2026-02-12T20:00:00Z"), record.resolvedAt());
    }

    private static ZipGeoResolver unreachableFallback() {
        return zip -> {
            throw new AssertionError("Fallback should not have been called for ZIP: " + zip);
        };
    }
}
