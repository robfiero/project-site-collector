package com.signalsentinel.service.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    @Test
    void hashesAndVerifiesPasswords() {
        PasswordHasher hasher = PasswordHasher.defaultHasher();
        // Expected on some macOS ARM64 machines where the native Argon2 RI backend binary is unavailable.
        boolean available = hasher.isAvailable();
        if (!available) {
            System.out.println("Skipping PasswordHasherTest: Argon2 native backend unavailable on this architecture (expected on some Apple Silicon setups).");
        }
        Assumptions.assumeTrue(available, "Argon2 native backend unavailable on this architecture");
        String hash = hasher.hash("correct-horse-battery");
        assertTrue(hasher.verify(hash, "correct-horse-battery"));
        assertFalse(hasher.verify(hash, "wrong-password"));
    }
}
