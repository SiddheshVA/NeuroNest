package com.neuronest.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

// Maps a verified Clerk identity onto the app's own User row: finds an
// existing link, backfills the link onto a pre-Clerk account with the same
// email, or provisions a brand-new local user.
@Service
public class ClerkUserService {

    private static final Logger log = LoggerFactory.getLogger(ClerkUserService.class);

    private final UserRepository userRepository;

    public ClerkUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User provision(String clerkUserId, String email, String name) {
        Optional<User> byClerkId = userRepository.findByClerkUserId(clerkUserId);
        if (byClerkId.isPresent()) {
            return byClerkId.get();
        }

        if (email == null || email.isBlank()) {
            log.warn("Clerk token for subject {} has no 'email' claim. Add an 'email' claim to the "
                    + "Clerk session token customization ({{{{user.primary_email_address}}}}) in the Clerk "
                    + "dashboard. Treating this request as unauthenticated.", clerkUserId);
            return null;
        }

        Optional<User> byEmail = userRepository.findByEmail(email);
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            user.setClerkUserId(clerkUserId);
            return userRepository.save(user);
        }

        User user = new User();
        user.setClerkUserId(clerkUserId);
        user.setEmail(email);
        user.setName(name);
        user.setEmailVerified(true);
        return userRepository.save(user);
    }
}
