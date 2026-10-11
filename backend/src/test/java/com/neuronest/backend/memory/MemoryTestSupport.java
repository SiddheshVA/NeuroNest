package com.neuronest.backend.memory;

import com.neuronest.backend.auth.User;
import com.neuronest.backend.chat.ChatConversation;
import com.neuronest.backend.chat.ChatMessage;
import com.neuronest.backend.session.Session;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

final class MemoryTestSupport {

    private MemoryTestSupport() {}

    static MemoryProperties properties(Duration recallBudget) {
        return properties(recallBudget, recallBudget);
    }

    static MemoryProperties properties(Duration recallBudget, Duration firstMessageRecallBudget) {
        return new MemoryProperties(true, "gemini-embedding-2", 768, 5, 0.60, recallBudget, firstMessageRecallBudget,
                Duration.ofSeconds(1), 0.90, 200, 5, 5, Duration.ofMinutes(30), Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    static MemoryProperties properties() {
        return properties(Duration.ofSeconds(2));
    }

    static User user(UUID id, boolean memoryEnabled) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setMemoryEnabled(memoryEnabled);
        return user;
    }

    static Session session(UUID id, UUID owner) {
        Session session = new Session();
        ReflectionTestUtils.setField(session, "id", id);
        session.setUserId(owner);
        return session;
    }

    static ChatConversation conversation(UUID id, UUID sessionId) {
        ChatConversation conversation = new ChatConversation();
        ReflectionTestUtils.setField(conversation, "id", id);
        conversation.setSessionId(sessionId);
        return conversation;
    }

    static ChatMessage message(String role, String content, boolean riskFlag) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        message.setRiskFlag(riskFlag);
        return message;
    }
}
