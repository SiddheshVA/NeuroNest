package com.neuronest.backend.admin;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

// Issues and verifies admin dashboard tokens. Completely separate from JwtService (user cookie)
// and Clerk: its own secret, issuer and role claim, and verification accepts nothing else.
@Component
public class AdminJwtService {

    public static final String ISSUER = "neuronest-admin";
    public static final String ROLE = "ADMIN";
    static final Duration VALIDITY = Duration.ofHours(2);
    private static final String ALGORITHM = "HS256";

    public record IssuedToken(String token, Instant expiresAt) {}

    private final SecretKey key;
    private final Clock clock;

    @Autowired
    public AdminJwtService(AdminProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AdminJwtService(AdminProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public IssuedToken issue(String username) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(VALIDITY);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(username)
                .claim("role", ROLE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                // Explicit: signWith(key) alone would pick HS384/HS512 for longer secrets.
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    // Subject of a valid admin token; empty for anything else (wrong key or algorithm, Clerk token,
    // user cookie token, expired, missing role/issuer/expiry, garbage).
    public Optional<String> verify(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .require("role", ROLE)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token);
            if (!ALGORITHM.equals(jws.getHeader().getAlgorithm()) || jws.getPayload().getExpiration() == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(jws.getPayload().getSubject());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
