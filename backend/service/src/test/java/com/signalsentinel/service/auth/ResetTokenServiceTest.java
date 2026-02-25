package com.signalsentinel.service.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResetTokenServiceTest {
    @Test
    void generatesDistinctTokensAndDeterministicHashes() {
        ResetTokenService service = new ResetTokenService();

        String first = service.generateRawToken();
        String second = service.generateRawToken();

        assertFalse(first.isBlank());
        assertFalse(second.isBlank());
        assertNotEquals(first, second);
        assertEquals(service.hashToken(first), service.hashToken(first));
        assertNotEquals(service.hashToken(first), service.hashToken(second));
    }
}
