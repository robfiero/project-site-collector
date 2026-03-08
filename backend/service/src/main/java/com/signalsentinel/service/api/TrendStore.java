package com.signalsentinel.service.api;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

final class TrendStore {
    private final Clock clock;
    private final int windowSeconds;
    private final int bucketSeconds;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final Object lock = new Object();

    TrendStore(Clock clock, int windowSeconds, int bucketSeconds) {
        if (windowSeconds <= 0 || bucketSeconds <= 0 || (windowSeconds % bucketSeconds) != 0) {
            throw new IllegalArgumentException("windowSeconds must be positive and divisible by bucketSeconds");
        }
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.windowSeconds = windowSeconds;
        this.bucketSeconds = bucketSeconds;
    }

    void record(String seriesKey, double value, Instant at) {
        if (seriesKey == null || seriesKey.isBlank()) {
            return;
        }
        Instant timestamp = at != null ? at : clock.instant();
        synchronized (lock) {
            trim(timestamp);
            samples.addLast(new Sample(seriesKey, value, timestamp));
        }
    }

    Map<String, Object> snapshot() {
        Instant now = clock.instant();
        synchronized (lock) {
            trim(now);
            return buildSnapshot(now);
        }
    }

    private Map<String, Object> buildSnapshot(Instant now) {
        int buckets = windowSeconds / bucketSeconds;
        long alignedNow = alignToBucket(now.getEpochSecond());
        long windowStart = alignedNow - ((long) (buckets - 1) * bucketSeconds);

        TreeSet<String> keys = new TreeSet<>();
        for (Sample sample : samples) {
            keys.add(sample.seriesKey());
        }

        List<Map<String, Object>> seriesList = new ArrayList<>();
        for (String key : keys) {
            double[] values = new double[buckets];
            for (Sample sample : samples) {
                if (!key.equals(sample.seriesKey())) {
                    continue;
                }
                long epoch = sample.timestamp().getEpochSecond();
                long idx = (epoch - windowStart) / bucketSeconds;
                if (idx >= 0 && idx < buckets) {
                    values[(int) idx] += sample.value();
                }
            }
            List<Map<String, Object>> points = new ArrayList<>(buckets);
            for (int i = 0; i < buckets; i++) {
                points.add(Map.of(
                        "timestamp", Instant.ofEpochSecond(windowStart + ((long) i * bucketSeconds)).toString(),
                        "value", values[i]
                ));
            }
            seriesList.add(Map.of("key", key, "points", points));
        }

        return new LinkedHashMap<>(Map.of(
                "asOf", now.toString(),
                "windowStart", Instant.ofEpochSecond(windowStart).toString(),
                "bucketSeconds", bucketSeconds,
                "series", seriesList
        ));
    }

    private void trim(Instant reference) {
        Instant cutoff = reference.minusSeconds(windowSeconds);
        while (!samples.isEmpty()) {
            Sample first = samples.peekFirst();
            if (first != null && first.timestamp().isBefore(cutoff)) {
                samples.removeFirst();
            } else {
                break;
            }
        }
    }

    private long alignToBucket(long epochSecond) {
        return epochSecond - Math.floorMod(epochSecond, bucketSeconds);
    }

    private record Sample(String seriesKey, double value, Instant timestamp) {
    }
}
