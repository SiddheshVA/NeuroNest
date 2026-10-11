package com.neuronest.backend.kb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Knowledge-base (wellness library) RAG tunables, bound from "kb.*" in application.properties.
// Every value has a default so nothing has to be configured to run.
@ConfigurationProperties(prefix = "kb")
public record KbProperties(
        // Global kill switch: false disables both ingestion and retrieval.
        @DefaultValue("true") boolean enabled,
        // Folder (Spring resource location) holding the Markdown docs, searched recursively.
        @DefaultValue("classpath:kb/") String location,
        // Chunks added to the prompt at most.
        @DefaultValue("3") int topK,
        // Cosine similarity a chunk must reach to be considered at all.
        @DefaultValue("0.60") double minSimilarity,
        // Nearest chunks fetched before the section preferences and safety rule pick the final topK.
        @DefaultValue("12") int candidatePool,
        // Added to the score of Overview and practical ("What you can try", "How to do it", ...) sections.
        @DefaultValue("0.03") double preferredSectionBoost,
        // Hard cap on how long retrieval may delay a chat reply; on timeout the reply goes out without it.
        @DefaultValue("2500ms") Duration retrievalBudget,
        // Characters from the end of the rolling summary added to the search query for context.
        @DefaultValue("300") int summaryContextChars,
        // No retrieval for the rest of a conversation once it had a crisis turn.
        @DefaultValue("true") boolean skipAfterCrisis,
        // Pause between embedding calls during ingestion, to stay under Gemini rate limits.
        @DefaultValue("150ms") Duration ingestDelay,
        // When ingestion stops early (e.g. rate limited), it retries after this long, up to ingestMaxAttempts.
        @DefaultValue("2m") Duration ingestRetryDelay,
        @DefaultValue("5") int ingestMaxAttempts
) {}
