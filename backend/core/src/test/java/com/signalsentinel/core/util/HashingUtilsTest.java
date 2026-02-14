package com.signalsentinel.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashingUtilsTest {
    @Test
    void sha256IsDeterministicAndHexEncoded() {
        String hash = HashingUtils.sha256("hello");

        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void sha256ProducesDifferentHashesForDifferentInputs() {
        String alpha = HashingUtils.sha256("alpha");
        String beta = HashingUtils.sha256("beta");

        assertNotEquals(alpha, beta);
    }
}
