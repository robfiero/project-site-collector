package com.signalsentinel.core.events;

import java.time.Instant;

public record NewsUpdated(Instant timestamp, String source, int storyCount) implements Event {
    @Override
    public String type() {
        return "NewsUpdated";
    }
}
