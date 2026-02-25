package com.signalsentinel.core.events;

import java.time.Instant;

public record UserRegistered(
        Instant timestamp,
        String userId,
        String email
) implements Event {
    @Override
    public String type() {
        return "UserRegistered";
    }
}
