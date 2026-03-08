package com.signalsentinel.service.api;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendStoreTest {
    @Test
    void bucketBoundaryAlignmentAggregatesWithinBucketAndPlacesBoundaryValueCorrectly() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-25T20:00:00Z"));
        TrendStore store = new TrendStore(clock, 600, 300);

        store.record("collector.runs.rssCollector.success", 1.0, Instant.parse("2026-02-25T19:55:01Z"));
        store.record("collector.runs.rssCollector.success", 2.0, Instant.parse("2026-02-25T19:59:59Z"));
        store.record("collector.runs.rssCollector.success", 4.0, Instant.parse("2026-02-25T20:00:00Z"));

        Map<String, Object> snapshot = store.snapshot();
        assertEquals("2026-02-25T20:00:00Z", snapshot.get("asOf"));
        assertEquals(300, snapshot.get("bucketSeconds"));
        assertEquals("2026-02-25T19:55:00Z", snapshot.get("windowStart"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) snapshot.get("series");
        assertEquals(1, series.size());
        assertEquals("collector.runs.rssCollector.success", series.get(0).get("key"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runPoints = (List<Map<String, Object>>) series.get(0).get("points");
        assertEquals(2, runPoints.size());
        assertEquals("2026-02-25T19:55:00Z", runPoints.get(0).get("timestamp"));
        assertEquals(3.0, ((Number) runPoints.get(0).get("value")).doubleValue());
        assertEquals("2026-02-25T20:00:00Z", runPoints.get(1).get("timestamp"));
        assertEquals(4.0, ((Number) runPoints.get(1).get("value")).doubleValue());
    }

    @Test
    void windowTrimmingDropsExpiredSamplesAndKeepsDeterministicBucketTimestamps() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-25T20:00:00Z"));
        TrendStore store = new TrendStore(clock, 600, 300);

        store.record("ingested.localEvents.ticketmaster", 1.0, Instant.parse("2026-02-25T19:49:59Z"));
        store.record("ingested.localEvents.ticketmaster", 2.0, Instant.parse("2026-02-25T19:50:00Z"));
        store.record("ingested.localEvents.ticketmaster", 4.0, Instant.parse("2026-02-25T19:59:00Z"));
        clock.set(Instant.parse("2026-02-25T20:00:00Z"));

        Map<String, Object> snapshot = store.snapshot();
        assertEquals("2026-02-25T19:55:00Z", snapshot.get("windowStart"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) snapshot.get("series");
        assertEquals(1, series.size());
        assertEquals("ingested.localEvents.ticketmaster", series.get(0).get("key"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) series.get(0).get("points");
        assertEquals(List.of("2026-02-25T19:55:00Z", "2026-02-25T20:00:00Z"),
                points.stream().map(point -> (String) point.get("timestamp")).toList());
        assertEquals(4.0, ((Number) points.get(0).get("value")).doubleValue());
        assertEquals(0.0, ((Number) points.get(1).get("value")).doubleValue());
    }

    @Test
    void multiSeriesIsolationAndBlankSeriesHandlingAreDeterministic() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-25T20:00:00Z"));
        TrendStore store = new TrendStore(clock, 600, 300);

        store.record("", 99.0, Instant.parse("2026-02-25T19:58:00Z"));
        store.record("ingested.news.cnn", 3.0, Instant.parse("2026-02-25T19:58:00Z"));
        store.record("ingested.localEvents.ticketmaster", 2.0, Instant.parse("2026-02-25T19:58:00Z"));

        Map<String, Object> snapshot = store.snapshot();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) snapshot.get("series");
        assertEquals(2, series.size());
        assertEquals("ingested.localEvents.ticketmaster", series.get(0).get("key"));
        assertEquals("ingested.news.cnn", series.get(1).get("key"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> localPoints = (List<Map<String, Object>>) series.get(0).get("points");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> newsPoints = (List<Map<String, Object>>) series.get(1).get("points");
        double localTotal = localPoints.stream().mapToDouble(point -> ((Number) point.get("value")).doubleValue()).sum();
        double newsTotal = newsPoints.stream().mapToDouble(point -> ((Number) point.get("value")).doubleValue()).sum();
        assertEquals(2.0, localTotal);
        assertEquals(3.0, newsTotal);
        assertFalse(localPoints.equals(newsPoints));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant value) {
            this.now = value;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
