package com.neuronest.backend.chat;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_conversations")
public class ChatConversation {

    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    /** Rolling summary of the messages older than the prompt's recent-message window. */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /** How many of the oldest messages {@link #summary} covers (null = none yet). */
    @Column(name = "summarized_message_count")
    private Integer summarizedMessageCount;

    public UUID getId() { return id; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public Instant getStartedAt() { return startedAt; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public int getSummarizedMessageCount() { return summarizedMessageCount == null ? 0 : summarizedMessageCount; }
    public void setSummarizedMessageCount(int count) { this.summarizedMessageCount = count; }
}