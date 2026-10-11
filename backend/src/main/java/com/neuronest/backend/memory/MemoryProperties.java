package com.neuronest.backend.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// All long-term memory tunables, bound from "memory.*" in application.properties.
// Every value has a default so nothing has to be configured to run.
@ConfigurationProperties(prefix = "memory")
public record MemoryProperties(
        // Global kill switch: false disables both recall and extraction for everyone.
        @DefaultValue("true") boolean enabled,
        @DefaultValue("gemini-embedding-2") String embeddingModel,
        @DefaultValue("768") int embeddingDimensions,
        @DefaultValue("5") int recallTopK,
        // Cosine similarity a memory must reach to be added to the prompt.
        @DefaultValue("0.60") double recallMinSimilarity,
        // Hard cap on how long recall may delay a chat reply; on timeout the reply goes out without memories.
        @DefaultValue("2500ms") Duration recallBudget,
        // Longer cap for the first message of a conversation, which may hit a database
        // that is still waking from idle suspend; later messages use recallBudget.
        @DefaultValue("6s") Duration firstMessageRecallBudget,
        // On the first message of a new conversation, recall first extracts the user's earlier
        // unfinished conversations (so what they just said there is remembered) for up to this
        // long; the rest of firstMessageRecallBudget is left for the lookup itself.
        @DefaultValue("4s") Duration firstMessageFlushBudget,
        // A new fact is skipped when an existing memory of the same user is more similar than this.
        @DefaultValue("0.90") double dedupSimilarity,
        @DefaultValue("200") int maxPerUser,
        @DefaultValue("5") int maxFactsPerRun,
        // Extraction runs once this many unprocessed user messages pile up in a conversation.
        @DefaultValue("5") int extractEveryUserMessages,
        // A conversation with no new messages for this long counts as ended and is swept.
        @DefaultValue("30m") Duration idleAfter,
        @DefaultValue("2s") Duration httpConnectTimeout,
        @DefaultValue("2s") Duration embeddingReadTimeout
) {}
