package com.neuronest.backend.chat.llm;

/** Every provider in the chain failed before producing anything usable. */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String step, Throwable lastFailure) {
        super("No LLM provider could serve the " + step, lastFailure);
    }
}
