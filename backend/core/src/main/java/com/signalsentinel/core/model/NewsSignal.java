package com.signalsentinel.core.model;

import java.time.Instant;
import java.util.List;

public record NewsSignal(
        String source,
        List<NewsStory> stories,
        Instant updatedAt
) {
}
