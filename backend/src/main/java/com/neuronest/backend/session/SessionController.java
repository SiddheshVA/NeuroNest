package com.neuronest.backend.session;

import com.neuronest.backend.auth.AuthUtil;
import com.neuronest.backend.memory.MemoryRecallService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionRepository sessionRepository;
    private final SessionAccessService sessionAccessService;
    private final MemoryRecallService memoryRecallService;

    public SessionController(SessionRepository sessionRepository, SessionAccessService sessionAccessService,
                             MemoryRecallService memoryRecallService) {
        this.sessionRepository = sessionRepository;
        this.sessionAccessService = sessionAccessService;
        this.memoryRecallService = memoryRecallService;
    }

    @PostMapping
    public ResponseEntity<Session> createSession(Authentication authentication) {
        Session session = new Session();
        session.setUserId(AuthUtil.currentUserId(authentication));
        Session saved = sessionRepository.save(session);
        // Starting a conversation: warm the database before the user's first message.
        memoryRecallService.warmUp(saved.getUserId());
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Session> getSession(@PathVariable UUID id, Authentication authentication) {
        Session session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + id));
        sessionAccessService.checkAccess(session, AuthUtil.currentUserId(authentication));
        return ResponseEntity.ok(session);
    }
}