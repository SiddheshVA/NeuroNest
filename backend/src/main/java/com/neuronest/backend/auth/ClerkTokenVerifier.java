package com.neuronest.backend.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Date;

// Verifies Clerk session tokens against Clerk's published JWKS. Never throws:
// any malformed/expired/mismatched token results in a null return, and the
// caller (ClerkAuthFilter) treats that exactly like "no token" (guest).
@Component
public class ClerkTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(ClerkTokenVerifier.class);

    public record ClerkClaims(String subject, String email, String name) {}

    private final JWTProcessor<SecurityContext> jwtProcessor;
    private final String issuer;

    public ClerkTokenVerifier(@Value("${clerk.jwks-url}") String jwksUrl,
                               @Value("${clerk.issuer}") String issuer) throws MalformedURLException {
        this.issuer = issuer;

        JWKSource<SecurityContext> jwkSource = new RemoteJWKSet<>(URI.create(jwksUrl).toURL());
        JWSVerificationKeySelector<SecurityContext> keySelector =
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource);

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(keySelector);
        this.jwtProcessor = processor;
    }

    public ClerkClaims verify(String token) {
        try {
            JWTClaimsSet claims = jwtProcessor.process(token, null);

            if (!issuer.equals(claims.getIssuer())) {
                log.debug("Clerk token rejected: issuer mismatch (expected {}, got {})", issuer, claims.getIssuer());
                return null;
            }

            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.before(new Date())) {
                log.debug("Clerk token rejected: expired or missing exp claim");
                return null;
            }

            String email = claims.getStringClaim("email");
            String name = claims.getStringClaim("name");
            return new ClerkClaims(claims.getSubject(), email, name);
        } catch (Exception e) {
            log.debug("Clerk token verification failed: {}", e.getMessage());
            return null;
        }
    }
}
