package com.neuronest.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {
    Optional<ChatConversation> findFirstBySessionIdOrderByStartedAtDesc(UUID sessionId);
    List<ChatConversation> findBySessionIdInOrderByStartedAtDesc(List<UUID> sessionIds);
    List<ChatConversation> findBySessionId(UUID sessionId);
}