package com.neuronest.backend.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

// Admin dashboard credentials, bound from "admin.*" (filled from the ADMIN_* environment variables).
// Validated on construction, so the app refuses to start without a usable admin config.
@ConfigurationProperties(prefix = "admin")
public record AdminProperties(String username, String password, String jwtSecret) {

    static final int MIN_SECRET_LENGTH = 32;

    public AdminProperties {
        List<String> problems = new ArrayList<>();
        if (username == null || username.isBlank()) {
            problems.add("ADMIN_USERNAME is not set");
        }
        if (password == null || password.isBlank()) {
            problems.add("ADMIN_PASSWORD is not set");
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            problems.add("ADMIN_JWT_SECRET is not set");
        } else if (jwtSecret.length() < MIN_SECRET_LENGTH) {
            problems.add("ADMIN_JWT_SECRET must be at least " + MIN_SECRET_LENGTH + " characters (got "
                    + jwtSecret.length() + ")");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid admin configuration: " + String.join("; ", problems)
                    + ". Set them as environment variables or in backend/.env.properties.");
        }
    }

    // Never print the password or secret, e.g. in a bind failure report.
    @Override
    public String toString() {
        return "AdminProperties[username=" + username + ", password=***, jwtSecret=***]";
    }
}
