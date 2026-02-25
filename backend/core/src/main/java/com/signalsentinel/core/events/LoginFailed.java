package com.signalsentinel.core.events;

import java.time.Instant;

public record LoginFailed(
        Instant timestamp,
        String email,
        String reason
) implements Event {
    @Override
    public String type() {
        return "LoginFailed";
    }
}
