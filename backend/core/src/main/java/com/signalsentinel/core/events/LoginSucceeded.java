package com.signalsentinel.core.events;

import java.time.Instant;

public record LoginSucceeded(
        Instant timestamp,
        String userId,
        String email
) implements Event {
    @Override
    public String type() {
        return "LoginSucceeded";
    }
}
