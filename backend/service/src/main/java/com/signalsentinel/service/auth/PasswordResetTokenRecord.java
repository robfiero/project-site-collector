package com.signalsentinel.service.auth;

import java.time.Instant;

public record PasswordResetTokenRecord(
        String tokenHash,
        String userId,
        Instant expiresAt,
        boolean used
) {
    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public PasswordResetTokenRecord markUsed() {
        return new PasswordResetTokenRecord(tokenHash, userId, expiresAt, true);
    }
}
