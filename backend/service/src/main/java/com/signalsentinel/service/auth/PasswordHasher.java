package com.signalsentinel.service.auth;

import com.kosprov.jargon2.api.Jargon2;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import static com.kosprov.jargon2.api.Jargon2.jargon2Verifier;

public final class PasswordHasher implements PasswordHashing {
    private static final String PORTABLE_PREFIX = "portable$";
    private static final String PBKDF2_PREFIX = "{pbkdf2}$";
    private static final String PBKDF2_ALGO_TAG = "sha256";
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int PBKDF2_SALT_BYTES = 16;
    private static final int PBKDF2_KEY_BITS = 256;

    private final int memoryCostKb;
    private final int timeCost;
    private final int parallelism;
    private final PasswordHashing delegate;

    public PasswordHasher(int memoryCostKb, int timeCost, int parallelism) {
        this.memoryCostKb = memoryCostKb;
        this.timeCost = timeCost;
        this.parallelism = parallelism;
        this.delegate = null;
    }

    private PasswordHasher(PasswordHashing delegate) {
        this.memoryCostKb = 0;
        this.timeCost = 0;
        this.parallelism = 0;
        this.delegate = delegate;
    }

    public static PasswordHasher defaultHasher() {
        return new PasswordHasher(19456, 2, 1);
    }

    /**
     * Demo/test-only SHA-256 hasher kept for backward compatibility with existing fixtures.
     * Do not use for production auth.
     */
    public static PasswordHasher portableForTests() {
        return new PasswordHasher(new PortableSha256Hasher());
    }

    /**
     * Portable fallback for environments where native Argon2 is unavailable.
     * Uses PBKDF2-HMAC-SHA256 in pure Java so it works on all supported JVMs.
     * This is significantly stronger than the legacy demo SHA-256 fallback.
     */
    public static PasswordHasher portablePbkdf2() {
        return new PasswordHasher(new PortablePbkdf2Hasher());
    }

    @Override
    public String hash(String password) {
        if (delegate != null) {
            return delegate.hash(password);
        }
        return Jargon2.jargon2Hasher()
                .type(Jargon2.Type.ARGON2id)
                .memoryCost(memoryCostKb)
                .timeCost(timeCost)
                .parallelism(parallelism)
                .password(password.getBytes())
                .encodedHash();
    }

    @Override
    public boolean verify(String encodedHash, String password) {
        if (encodedHash == null || password == null) {
            return false;
        }
        if (encodedHash.startsWith(PBKDF2_PREFIX)) {
            return PortablePbkdf2Hasher.verifyPbkdf2(encodedHash, password);
        }
        if (encodedHash.startsWith(PORTABLE_PREFIX)) {
            return PortableSha256Hasher.verifyPortableSha256(encodedHash, password);
        }
        try {
            return jargon2Verifier()
                    .hash(encodedHash)
                    .password(password.getBytes())
                    .verifyEncoded();
        } catch (RuntimeException | UnsatisfiedLinkError unavailable) {
            return false;
        }
    }

    public boolean isAvailable() {
        if (delegate != null) {
            return true;
        }
        try {
            hash("argon2-availability-probe");
            return true;
        } catch (RuntimeException | UnsatisfiedLinkError unavailable) {
            return false;
        }
    }

    private static final class PortableSha256Hasher implements PasswordHashing {
        private static final SecureRandom RANDOM = new SecureRandom();

        @Override
        public String hash(String password) {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] digest = sha256(salt, password);
            return PORTABLE_PREFIX + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(digest);
        }

        @Override
        public boolean verify(String encodedHash, String password) {
            return verifyPortableSha256(encodedHash, password);
        }

        private static boolean verifyPortableSha256(String encodedHash, String password) {
            if (encodedHash == null || !encodedHash.startsWith(PORTABLE_PREFIX) || password == null) {
                return false;
            }
            String[] parts = encodedHash.split("\\$");
            if (parts.length != 3) {
                return false;
            }
            byte[] salt;
            byte[] expected;
            try {
                salt = Base64.getDecoder().decode(parts[1]);
                expected = Base64.getDecoder().decode(parts[2]);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            byte[] actual = sha256(salt, password);
            return MessageDigest.isEqual(expected, actual);
        }

        private static byte[] sha256(byte[] salt, String password) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(salt);
                digest.update(password.getBytes(StandardCharsets.UTF_8));
                return digest.digest();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 not available", impossible);
            }
        }
    }

    private static final class PortablePbkdf2Hasher implements PasswordHashing {
        private static final SecureRandom RANDOM = new SecureRandom();

        @Override
        public String hash(String password) {
            byte[] salt = new byte[PBKDF2_SALT_BYTES];
            RANDOM.nextBytes(salt);
            byte[] derivedKey = derive(password, salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
            return PBKDF2_PREFIX
                    + PBKDF2_ALGO_TAG + "$"
                    + PBKDF2_ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(derivedKey);
        }

        @Override
        public boolean verify(String encodedHash, String password) {
            return verifyPbkdf2(encodedHash, password);
        }

        private static boolean verifyPbkdf2(String encodedHash, String password) {
            if (encodedHash == null || password == null || !encodedHash.startsWith(PBKDF2_PREFIX)) {
                return false;
            }
            String[] parts = encodedHash.split("\\$");
            if (parts.length != 5) {
                return false;
            }
            if (!PBKDF2_ALGO_TAG.equalsIgnoreCase(parts[1])) {
                return false;
            }
            int iterations;
            try {
                iterations = Integer.parseInt(parts[2]);
            } catch (NumberFormatException invalid) {
                return false;
            }
            byte[] salt;
            byte[] expected;
            try {
                salt = Base64.getDecoder().decode(parts[3]);
                expected = Base64.getDecoder().decode(parts[4]);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            byte[] actual = derive(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        }

        private static byte[] derive(String password, byte[] salt, int iterations, int keyBits) {
            try {
                SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyBits);
                return keyFactory.generateSecret(keySpec).getEncoded();
            } catch (Exception failure) {
                throw new IllegalStateException("PBKDF2 hashing unavailable", failure);
            }
        }
    }
}
