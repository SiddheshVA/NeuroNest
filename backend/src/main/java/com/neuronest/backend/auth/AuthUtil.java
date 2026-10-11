package com.neuronest.backend.auth;

import org.springframework.security.core.Authentication;

import java.util.UUID;

public class AuthUtil {

    public static UUID currentUserId(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return (principal instanceof UUID userId) ? userId : null;
    }
}