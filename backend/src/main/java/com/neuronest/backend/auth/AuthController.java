package com.neuronest.backend.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String COOKIE_NAME = "auth_token";
    private static final int COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
                          JwtService jwtService, OtpService otpService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.otpService = otpService;
    }

    public record RegisterRequest(String name, String email, String password) {}
    public record LoginRequest(String email, String password) {}
    public record OtpRequest(String email, String otp) {}
    public record EmailRequest(String email) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request, HttpServletResponse response) {
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));
        }
        if (request.email() == null || request.password() == null || request.password().length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and a password of at least 8 characters are required."));
        }
        if (userRepository.existsByEmail(request.email())) {
            return ResponseEntity.status(409).body(Map.of("error", "An account with this email already exists."));
        }

        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        User saved = userRepository.save(user);

        otpService.generateAndSendOtp(saved);
        setAuthCookie(response, saved.getId());
        return ResponseEntity.ok(Map.of("id", saved.getId(), "name", saved.getName(), "email", saved.getEmail(), "emailVerified", false));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        Optional<User> userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty() || !passwordEncoder.matches(request.password(), userOpt.get().getPasswordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid email or password."));
        }

        User user = userOpt.get();
        setAuthCookie(response, user.getId());
        return ResponseEntity.ok(Map.of("id", user.getId(), "name", Objects.requireNonNullElse(user.getName(), ""), "email", user.getEmail(), "emailVerified", user.isEmailVerified()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        clearAuthCookie(response);
        return ResponseEntity.ok(Map.of("message", "Logged out."));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestBody OtpRequest request) {
        Optional<User> userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No account with this email."));
        }
        boolean verified = otpService.verifyOtp(userOpt.get(), request.otp());
        if (!verified) {
            return ResponseEntity.status(400).body(Map.of("error", "Invalid or expired code."));
        }
        return ResponseEntity.ok(Map.of("message", "Email verified."));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<?> resendOtp(@RequestBody EmailRequest request) {
        Optional<User> userOpt = userRepository.findByEmail(request.email());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No account with this email."));
        }
        if (userOpt.get().isEmailVerified()) {
            return ResponseEntity.ok(Map.of("message", "Already verified."));
        }
        otpService.generateAndSendOtp(userOpt.get());
        return ResponseEntity.ok(Map.of("message", "Verification code sent."));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return ResponseEntity.ok(Map.of("authenticated", false));
        }
        return userRepository.findById(userId)
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(Map.of(
                        "authenticated", true,
                        "id", u.getId(),
                        "name", Objects.requireNonNullElse(u.getName(), ""),
                        "email", u.getEmail(),
                        "emailVerified", u.isEmailVerified())))
                .orElse(ResponseEntity.ok(Map.of("authenticated", false)));
    }

    private void setAuthCookie(HttpServletResponse response, UUID userId) {
        String token = jwtService.generateToken(userId);
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(COOKIE_MAX_AGE_SECONDS)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearAuthCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}