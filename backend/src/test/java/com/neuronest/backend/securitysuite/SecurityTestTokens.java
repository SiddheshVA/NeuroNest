package com.neuronest.backend.securitysuite;

import com.neuronest.backend.admin.AdminJwtService;
import com.neuronest.backend.admin.AdminProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Test-only tokens for the security suite. Clerk tokens are real RS256 JWTs signed with a key whose
 * public half is served from a local JWKS endpoint, so the production ClerkTokenVerifier checks them
 * exactly as it checks Clerk's (signature, issuer, expiry). Admin tokens are issued by the production
 * AdminJwtService with a test secret. Nothing here is used by production code, and tokens are never
 * printed.
 */
final class SecurityTestTokens {

    static final String CLERK_ISSUER = "https://clerk.security-suite.test";
    static final String ADMIN_USERNAME = "suite-admin";
    static final String ADMIN_PASSWORD = "suite-admin-password";
    static final String ADMIN_SECRET = "security-suite-admin-secret-0123456789abcdef";
    private static final String OTHER_SECRET = "a-different-admin-secret-that-is-long-enough-0123456789";
    private static final String KEY_ID = "suite-key";

    private static RSAKey signingKey;
    private static RSAKey impostorKey;
    private static String jwksUrl;

    private SecurityTestTokens() {}

    /** Starts the local JWKS endpoint once per test run and returns its address. */
    static synchronized String jwksUrl() {
        if (jwksUrl != null) return jwksUrl;
        try {
            signingKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
            // Same key id, different key: a token signed with it must be refused.
            impostorKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
            byte[] jwks = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            jwksUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json";
            return jwksUrl;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- Clerk ----

    private static JWTClaimsSet clerkClaims(String subject, String email, Instant expires) {
        return new JWTClaimsSet.Builder()
                .issuer(CLERK_ISSUER)
                .subject(subject)
                .claim("email", email)
                .issueTime(Date.from(Instant.now().minusSeconds(60)))
                .expirationTime(Date.from(expires))
                .build();
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) {
        jwksUrl();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A valid Clerk session token. */
    static String clerk(String subject, String email) {
        return sign(signingKey, clerkClaims(subject, email, Instant.now().plus(Duration.ofMinutes(10))));
    }

    static String clerkWrongKey(String subject, String email) {
        jwksUrl();
        return sign(impostorKey, clerkClaims(subject, email, Instant.now().plus(Duration.ofMinutes(10))));
    }

    static String clerkExpired(String subject, String email) {
        return sign(signingKey, clerkClaims(subject, email, Instant.now().minus(Duration.ofMinutes(10))));
    }

    /** An unsigned token ("alg": "none") with otherwise valid claims. */
    static String clerkAlgNone(String subject, String email) {
        return new PlainJWT(clerkClaims(subject, email, Instant.now().plus(Duration.ofMinutes(10)))).serialize();
    }

    // ---- Admin ----

    static String admin() {
        return new AdminJwtService(new AdminProperties(ADMIN_USERNAME, ADMIN_PASSWORD, ADMIN_SECRET)).issue(ADMIN_USERNAME).token();
    }

    static String adminWrongSecret() {
        return new AdminJwtService(new AdminProperties(ADMIN_USERNAME, ADMIN_PASSWORD, OTHER_SECRET)).issue(ADMIN_USERNAME).token();
    }

    static String adminExpired() {
        Instant past = Instant.now().minus(Duration.ofHours(3));
        return Jwts.builder()
                .issuer(AdminJwtService.ISSUER)
                .subject(ADMIN_USERNAME)
                .claim("role", AdminJwtService.ROLE)
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plus(Duration.ofHours(2))))
                .signWith(Keys.hmacShaKeyFor(ADMIN_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    /** An unsigned admin token ("alg": "none") with the right issuer, role and a future expiry. */
    static String adminAlgNone() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        long exp = Instant.now().plus(Duration.ofHours(1)).getEpochSecond();
        String payload = b64.encodeToString(("{\"iss\":\"" + AdminJwtService.ISSUER + "\",\"sub\":\"" + ADMIN_USERNAME
                + "\",\"role\":\"" + AdminJwtService.ROLE + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    static final String GARBAGE = "this-is-not-a-token";
}
