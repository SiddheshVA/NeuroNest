package com.neuronest.backend.admin;

import com.neuronest.backend.auth.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminJwtServiceTest {

    private static final String SECRET = "admin-secret-admin-secret-admin-secret-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final AdminProperties properties = new AdminProperties("admin", "pw", SECRET);
    private final AdminJwtService service = new AdminJwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuedTokenVerifiesAndExpiresInTwoHours() {
        AdminJwtService.IssuedToken issued = service.issue("admin");

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(service.verify(issued.token())).contains("admin");
        assertThat(Jwts.parser().verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .clock(() -> Date.from(NOW)).build()
                .parseSignedClaims(issued.token()).getHeader().getAlgorithm()).isEqualTo("HS256");
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.issue("admin").token();
        AdminJwtService later = new AdminJwtService(properties,
                Clock.fixed(NOW.plus(Duration.ofHours(2)).plusSeconds(1), ZoneOffset.UTC));

        assertThat(later.verify(token)).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        AdminJwtService other = new AdminJwtService(
                new AdminProperties("admin", "pw", "another-secret-another-secret-0123456789"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.verify(other.issue("admin").token())).isEmpty();
    }

    @Test
    void rejectsUserCookieTokenEvenWithTheSameSecret() {
        String userToken = new JwtService(SECRET).generateToken(UUID.randomUUID());

        assertThat(service.verify(userToken)).isEmpty();
    }

    @Test
    void rejectsWrongRoleOrIssuerOrMissingExpiry() {
        var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date exp = Date.from(NOW.plus(Duration.ofHours(1)));

        String wrongRole = Jwts.builder().issuer(AdminJwtService.ISSUER).claim("role", "USER")
                .expiration(exp).signWith(key, Jwts.SIG.HS256).compact();
        String wrongIssuer = Jwts.builder().issuer("someone-else").claim("role", "ADMIN")
                .expiration(exp).signWith(key, Jwts.SIG.HS256).compact();
        String noExpiry = Jwts.builder().issuer(AdminJwtService.ISSUER).claim("role", "ADMIN")
                .signWith(key, Jwts.SIG.HS256).compact();
        String hs512 = Jwts.builder().issuer(AdminJwtService.ISSUER).claim("role", "ADMIN")
                .expiration(exp).signWith(Keys.hmacShaKeyFor((SECRET + SECRET).getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS512).compact();

        assertThat(service.verify(wrongRole)).isEmpty();
        assertThat(service.verify(wrongIssuer)).isEmpty();
        assertThat(service.verify(noExpiry)).isEmpty();
        assertThat(service.verify(hs512)).isEmpty();
    }

    @Test
    void rejectsUnsignedAndGarbageTokens() {
        String unsigned = Jwts.builder().issuer(AdminJwtService.ISSUER).claim("role", "ADMIN")
                .expiration(Date.from(NOW.plus(Duration.ofHours(1)))).compact();

        assertThat(service.verify(unsigned)).isEmpty();
        assertThat(service.verify("garbage")).isEmpty();
        assertThat(service.verify("")).isEmpty();
    }

    @Test
    void propertiesRejectMissingValuesAndShortSecret() {
        assertThatThrownBy(() -> new AdminProperties("", null, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_USERNAME is not set")
                .hasMessageContaining("ADMIN_PASSWORD is not set")
                .hasMessageContaining("ADMIN_JWT_SECRET is not set");
        assertThatThrownBy(() -> new AdminProperties("admin", "pw", "x".repeat(31)))
                .hasMessageContaining("ADMIN_JWT_SECRET must be at least 32 characters");
        assertThat(new AdminProperties("admin", "pw", "x".repeat(32)).toString()).doesNotContain("pw", "xxx");
    }
}
