package com.signalsentinel.service.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

public final class JwtService {
    private final Algorithm algorithm;
    private final Clock clock;
    private final Duration ttl;

    public JwtService(String secret, Clock clock, Duration ttl) {
        this.algorithm = Algorithm.HMAC256(secret);
        this.clock = clock;
        this.ttl = ttl;
    }

    public String issue(String userId, String email) {
        Instant now = clock.instant();
        return JWT.create()
                .withSubject(userId)
                .withClaim("email", email)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plus(ttl)))
                .sign(algorithm);
    }

    public Optional<Claims> verify(String token) {
        try {
            DecodedJWT jwt = JWT.require(algorithm).build().verify(token);
            if (jwt.getExpiresAt() != null && jwt.getExpiresAt().toInstant().isBefore(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(new Claims(jwt.getSubject(), jwt.getClaim("email").asString()));
        } catch (RuntimeException invalidToken) {
            return Optional.empty();
        }
    }

    public record Claims(String userId, String email) {
    }
}
