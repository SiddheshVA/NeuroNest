package com.neuronest.backend.memory;

import org.springframework.web.client.RestClientException;

import java.time.Duration;

// Gemini answered 429 (rate limited) or 503 (overloaded), or is still inside a
// back-off window from an earlier such answer. The work should be retried
// later, not dropped. The message is deliberately short (no response body).
public class GeminiRetryableException extends RestClientException {

    private final Duration retryAfter;

    public GeminiRetryableException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() { return retryAfter; }
}
