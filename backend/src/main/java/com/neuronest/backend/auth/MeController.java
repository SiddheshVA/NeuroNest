package com.neuronest.backend.auth;

import com.neuronest.backend.assessment.AssessmentResult;
import com.neuronest.backend.assessment.AssessmentResultRepository;
import com.neuronest.backend.chat.ChatConversation;
import com.neuronest.backend.chat.ChatConversationRepository;
import com.neuronest.backend.chat.ChatMessage;
import com.neuronest.backend.chat.ChatMessageRepository;
import com.neuronest.backend.memory.MemoryRepository;
import com.neuronest.backend.session.Session;
import com.neuronest.backend.session.SessionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private static final int PREVIEW_MAX_LENGTH = 80;

    private final SessionRepository sessionRepository;
    private final AssessmentResultRepository assessmentResultRepository;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MemoryRepository memoryRepository;

    public MeController(SessionRepository sessionRepository,
                        AssessmentResultRepository assessmentResultRepository,
                        ChatConversationRepository conversationRepository,
                        ChatMessageRepository messageRepository,
                        UserRepository userRepository,
                        MemoryRepository memoryRepository) {
        this.sessionRepository = sessionRepository;
        this.assessmentResultRepository = assessmentResultRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.memoryRepository = memoryRepository;
    }

    public record SessionSummary(UUID id, Instant startedAt, String preferredLanguage,
                                 long assessmentCount, boolean hasChatHistory) {}

    @GetMapping("/sessions")
    public ResponseEntity<?> mySessions(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Log in to view your history."));
        }

        List<Session> sessions = sessionRepository.findByUserIdOrderByStartedAtDesc(userId);

        List<SessionSummary> summaries = sessions.stream()
                .map(s -> new SessionSummary(
                        s.getId(),
                        s.getStartedAt(),
                        s.getPreferredLanguage(),
                        assessmentResultRepository.findBySessionIdOrderByCreatedAtDesc(s.getId()).size(),
                        conversationRepository.findFirstBySessionIdOrderByStartedAtDesc(s.getId()).isPresent()
                ))
                .toList();

        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Log in to view your dashboard."));
        }

        return userRepository.findById(userId)
                .<ResponseEntity<?>>map(user -> {
                    List<Session> sessions = sessionRepository.findByUserIdOrderByStartedAtDesc(userId);
                    List<UUID> sessionIds = sessions.stream().map(Session::getId).toList();
                    List<AssessmentResult> assessments = sessionIds.isEmpty()
                            ? List.of()
                            : assessmentResultRepository.findBySessionIdInOrderByCreatedAtDesc(sessionIds);

                    return ResponseEntity.ok(Map.of(
                            "name", Objects.requireNonNullElse(user.getName(), ""),
                            "email", user.getEmail(),
                            "totalSessions", sessions.size(),
                            "assessments", assessments
                    ));
                })
                .orElse(ResponseEntity.status(401).body(Map.of("error", "Log in to view your dashboard.")));
    }

    public record ConversationSummary(UUID sessionId, Instant startedAt, String preview, int messageCount) {}

    @GetMapping("/conversations")
    public ResponseEntity<?> myConversations(Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Log in to view your conversations."));
        }

        List<Session> sessions = sessionRepository.findByUserIdOrderByStartedAtDesc(userId);
        List<UUID> sessionIds = sessions.stream().map(Session::getId).toList();
        List<ChatConversation> conversations = sessionIds.isEmpty()
                ? List.of()
                : conversationRepository.findBySessionIdInOrderByStartedAtDesc(sessionIds);

        List<ConversationSummary> summaries = conversations.stream()
                .map(conversation -> {
                    List<ChatMessage> messages = messageRepository
                            .findByConversationIdOrderByCreatedAtAsc(conversation.getId());
                    String preview = messages.stream()
                            .filter(m -> "user".equals(m.getRole()))
                            .findFirst()
                            .map(m -> truncate(m.getContent()))
                            .orElse("New conversation");

                    return new ConversationSummary(
                            conversation.getSessionId(),
                            conversation.getStartedAt(),
                            preview,
                            messages.size()
                    );
                })
                .toList();

        return ResponseEntity.ok(summaries);
    }

    // Deletes the chat history of one of the user's sessions, plus any long-term
    // memories extracted from it. The session itself (and any assessments taken
    // in it) is kept.
    @DeleteMapping("/conversations/{sessionId}")
    @Transactional
    public ResponseEntity<?> deleteConversation(@PathVariable UUID sessionId, Authentication authentication) {
        UUID userId = AuthUtil.currentUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Log in to manage your conversations."));
        }

        boolean owned = sessionRepository.findById(sessionId)
                .map(session -> userId.equals(session.getUserId()))
                .orElse(false);
        if (!owned) {
            return ResponseEntity.status(404).body(Map.of("error", "Conversation not found."));
        }

        for (ChatConversation conversation : conversationRepository.findBySessionId(sessionId)) {
            memoryRepository.deleteBySourceConversation(userId, conversation.getId());
            messageRepository.deleteByConversationId(conversation.getId());
            conversationRepository.delete(conversation);
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    private static String truncate(String content) {
        if (content == null) {
            return "New conversation";
        }
        if (content.length() <= PREVIEW_MAX_LENGTH) {
            return content;
        }
        return content.substring(0, PREVIEW_MAX_LENGTH) + "...";
    }
}