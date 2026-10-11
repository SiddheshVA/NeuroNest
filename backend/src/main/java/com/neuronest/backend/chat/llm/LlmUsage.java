package com.neuronest.backend.chat.llm;

/** Token counts reported by a provider for one call (either may be null if not reported). */
public record LlmUsage(Integer promptTokens, Integer completionTokens) {}
