package com.signalsentinel.core.events;

import java.time.Instant;

public record ContentChanged(
        Instant timestamp,
        String siteId,
        String url,
        String oldHash,
        String newHash
) implements Event {
    @Override
    public String type() {
        return "ContentChanged";
    }
}
