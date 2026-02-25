package com.signalsentinel.service.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherPortableTest {
    @Test
    void portablePbkdf2HasherHashesAndVerifiesWithoutNativeBackend() {
        PasswordHasher hasher = PasswordHasher.portablePbkdf2();

        String hash = hasher.hash("secret-value");
        assertTrue(hash.startsWith("{pbkdf2}$"));
        assertTrue(hasher.verify(hash, "secret-value"));
        assertFalse(hasher.verify(hash, "wrong-value"));
    }

    @Test
    void verifySupportsLegacyPortableSha256Hashes() {
        PasswordHasher legacy = PasswordHasher.portableForTests();
        PasswordHasher hasher = PasswordHasher.portablePbkdf2();

        String legacyHash = legacy.hash("secret-value");
        assertTrue(hasher.verify(legacyHash, "secret-value"));
        assertFalse(hasher.verify(legacyHash, "wrong-value"));
    }
}
