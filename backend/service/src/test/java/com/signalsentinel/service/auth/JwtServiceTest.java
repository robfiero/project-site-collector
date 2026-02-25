package com.signalsentinel.service.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {
    @Test
    void issueAndVerifyRoundTrip() {
        JwtService service = new JwtService(
                "test-secret",
                Clock.systemUTC(),
                Duration.ofHours(1)
        );

        String token = service.issue("u-1", "user@example.com");
        JwtService.Claims claims = service.verify(token).orElseThrow();
        assertEquals("u-1", claims.userId());
        assertEquals("user@example.com", claims.email());
    }

    @Test
    void verifyReturnsEmptyForTamperedToken() {
        JwtService service = new JwtService(
                "test-secret",
                Clock.systemUTC(),
                Duration.ofHours(1)
        );

        String token = service.issue("u-1", "user@example.com");
        String tampered = token + "x";
        assertTrue(service.verify(tampered).isEmpty());
    }

    @Test
    void verifyReturnsEmptyForMalformedToken() {
        JwtService service = new JwtService(
                "test-secret",
                Clock.systemUTC(),
                Duration.ofHours(1)
        );
        assertTrue(service.verify("not.a.jwt").isEmpty());
    }

    @Test
    void verifyReturnsEmptyWhenTokenExpiredRelativeToVerifierClock() {
        Instant issuedAt = Instant.parse("2026-02-25T10:00:00Z");
        JwtService issuer = new JwtService(
                "test-secret",
                Clock.fixed(issuedAt, ZoneOffset.UTC),
                Duration.ofMinutes(5)
        );
        String token = issuer.issue("u-1", "user@example.com");

        JwtService verifier = new JwtService(
                "test-secret",
                Clock.fixed(issuedAt.plus(Duration.ofMinutes(6)), ZoneOffset.UTC),
                Duration.ofHours(1)
        );
        assertTrue(verifier.verify(token).isEmpty());
    }
}
