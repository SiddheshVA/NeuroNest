package com.neuronest.backend.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);
    void deleteByConversationId(UUID conversationId);
}