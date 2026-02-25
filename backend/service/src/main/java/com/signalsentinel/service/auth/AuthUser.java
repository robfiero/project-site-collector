package com.signalsentinel.service.auth;

import java.time.Instant;

public record AuthUser(
        String id,
        String email,
        String passwordHash,
        Instant createdAt,
        Instant lastLoginAt
) {
    public AuthUser withLastLoginAt(Instant value) {
        return new AuthUser(id, email, passwordHash, createdAt, value);
    }

    public AuthUser withPasswordHash(String value) {
        return new AuthUser(id, email, value, createdAt, lastLoginAt);
    }
}
