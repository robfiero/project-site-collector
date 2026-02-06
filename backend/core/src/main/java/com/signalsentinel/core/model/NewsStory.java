package com.signalsentinel.core.model;

import java.time.Instant;

public record NewsStory(
        String title,
        String link,
        Instant publishedAt,
        String source
) {
}
