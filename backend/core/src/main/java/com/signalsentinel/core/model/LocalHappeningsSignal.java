package com.signalsentinel.core.model;

import java.time.Instant;
import java.util.List;

public record LocalHappeningsSignal(
        String location,
        List<String> headlines,
        Instant updatedAt
) {
}
