package com.signalsentinel.core.events;

import java.time.Instant;

public record PasswordResetRequested(
        Instant timestamp,
        String email
) implements Event {
    @Override
    public String type() {
        return "PasswordResetRequested";
    }
}
