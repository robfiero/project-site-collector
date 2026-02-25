package com.signalsentinel.service.auth;

import com.signalsentinel.core.util.HashingUtils;

import java.security.SecureRandom;
import java.util.Base64;

public final class ResetTokenService {
    private final SecureRandom random = new SecureRandom();

    public String generateRawToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String hashToken(String rawToken) {
        return HashingUtils.sha256(rawToken);
    }
}
