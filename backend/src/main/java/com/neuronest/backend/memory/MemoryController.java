package com.neuronest.backend.memory;

import com.neuronest.backend.auth.AuthUtil;
import com.neuronest.backend.auth.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

// Lets a logged-in user see and control what the AI Companion remembers.
// Every operation is scoped to the authenticated user's own id.
@RestController
@RequestMapping("/api/me")
public class MemoryController {

    private static final String LOGIN_REQUIRED = "Log in to manage your memories.";

    private final MemoryRepository memoryRepository;
    private final UserRepository userRepository;

    public MemoryController(MemoryRepository memoryRepository, UserRepository userRepository) {
        this.memoryRepository = memoryRepository;
        this.userRepository = userRepository;
    }

    public record MemorySettingsRequest(@NotNull Boolean enabled) {}

    @GetMapping("/memories")
    public ResponseEntity<?> listMemories(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(memoryRepository.findAllForUser(userId));
    }

    @DeleteMapping("/memories/{memoryId}")
    public ResponseEntity<?> deleteMemory(@PathVariable UUID memoryId, Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return unauthorized();
        }
        if (!memoryRepository.deleteOne(userId, memoryId)) {
            // Same response whether it doesn't exist or belongs to someone else.
            return ResponseEntity.status(404).body(Map.of("error", "Memory not found."));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/memories")
    public ResponseEntity<?> deleteAllMemories(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(Map.of("deleted", memoryRepository.deleteAll(userId)));
    }

    @GetMapping("/memory-settings")
    public ResponseEntity<?> getMemorySettings(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return unauthorized();
        }
        return userRepository.findById(userId)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(Map.of("enabled", user.isMemoryEnabled())))
                .orElseGet(MemoryController::unauthorized);
    }

    @PutMapping("/memory-settings")
    public ResponseEntity<?> updateMemorySettings(@Valid @RequestBody MemorySettingsRequest request,
                                                  Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return unauthorized();
        }
        return userRepository.findById(userId)
                .<ResponseEntity<?>>map(user -> {
                    user.setMemoryEnabled(request.enabled());
                    userRepository.save(user);
                    return ResponseEntity.ok(Map.of("enabled", user.isMemoryEnabled()));
                })
                .orElseGet(MemoryController::unauthorized);
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", LOGIN_REQUIRED));
    }
}
