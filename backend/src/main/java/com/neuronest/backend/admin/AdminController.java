package com.neuronest.backend.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminProperties properties;
    private final AdminJwtService adminJwtService;
    private final AdminLoginRateLimiter rateLimiter;

    public AdminController(AdminProperties properties, AdminJwtService adminJwtService,
                           AdminLoginRateLimiter rateLimiter) {
        this.properties = properties;
        this.adminJwtService = adminJwtService;
        this.rateLimiter = rateLimiter;
    }

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, Instant expiresAt) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        // Remote address only: X-Forwarded-For is client-controlled and would let anyone dodge the limit.
        String ip = httpRequest.getRemoteAddr();

        Optional<Duration> blocked = rateLimiter.blockedFor(ip);
        if (blocked.isPresent()) {
            long retryAfterSeconds = Math.max(1, (blocked.get().toMillis() + 999) / 1000);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                    .body(Map.of("error", "Too many failed login attempts. Try again later."));
        }

        // Non-short-circuit &: both fields are always compared, so timing doesn't reveal which one failed.
        boolean valid = constantTimeEquals(request.username(), properties.username())
                & constantTimeEquals(request.password(), properties.password());
        if (!valid) {
            rateLimiter.recordFailure(ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid credentials."));
        }

        rateLimiter.recordSuccess(ip);
        AdminJwtService.IssuedToken issued = adminJwtService.issue(properties.username());
        return ResponseEntity.ok(new LoginResponse(issued.token(), issued.expiresAt()));
    }

    // Reaching this method means AdminAuthFilter accepted the token (see SecurityConfig).
    @GetMapping("/me")
    public Map<String, String> me() {
        return Map.of("role", AdminJwtService.ROLE);
    }

    // Compares SHA-256 digests with MessageDigest.isEqual, so the time taken depends on neither
    // the content nor the length of the submitted value.
    static boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(sha256(Objects.requireNonNullElse(provided, "")), sha256(expected));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
