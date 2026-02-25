package com.signalsentinel.core.model;

import java.time.Instant;
import java.util.List;

public record LocalHappeningsSignal(
        String location,
        List<HappeningItem> items,
        String sourceAttribution,
        Instant updatedAt
) {
}
