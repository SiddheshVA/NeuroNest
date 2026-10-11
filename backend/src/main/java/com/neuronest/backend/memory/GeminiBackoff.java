package com.neuronest.backend.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Per-model back-off for the memory feature's Gemini embedding calls (chat
// replies and fact extraction go through the LLM router, never through
// here). After a 429/503 we stop calling that model until the server's
// retryDelay has passed, so we never retry sooner than Gemini asked.
@Component
public class GeminiBackoff {

    private static final Logger log = LoggerFactory.getLogger(GeminiBackoff.class);
    static final Duration DEFAULT_DELAY = Duration.ofSeconds(60);
    static final Duration MAX_DELAY = Duration.ofHours(1);

    private final JsonMapper jsonMapper;
    private final Map<String, Instant> blockedUntil = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public GeminiBackoff(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public static boolean isRetryable(HttpStatusCode status) {
        return status.value() == 429 || status.value() == 503;
    }

    public boolean isBlocked(String model) {
        Instant until = blockedUntil.get(model);
        return until != null && clock.instant().isBefore(until);
    }

    // Call before every request; throws without contacting Gemini while backing off.
    public void checkAvailable(String model) {
        Instant until = blockedUntil.get(model);
        if (until != null && clock.instant().isBefore(until)) {
            throw new GeminiRetryableException("Gemini model " + model + " backing off until " + until,
                    Duration.between(clock.instant(), until));
        }
    }

    // Registers RestClient handling: 429/503 start a back-off and become a
    // GeminiRetryableException instead of an HttpStatusCodeException carrying the body.
    public RestClient.Builder applyTo(RestClient.Builder builder, String model) {
        return builder.defaultStatusHandler(GeminiBackoff::isRetryable, (request, response) -> {
            throw onRetryableResponse(model, response.getStatusCode(), response.getHeaders(),
                    response.getBody().readAllBytes());
        });
    }

    GeminiRetryableException onRetryableResponse(String model, HttpStatusCode status, HttpHeaders headers, byte[] body) {
        Duration delay = retryDelay(headers, body);
        Instant until = clock.instant().plus(delay);
        blockedUntil.merge(model, until, (a, b) -> a.isAfter(b) ? a : b);

        String reason = status.value() == 429 ? "rate limited (429)" : "unavailable (503)";
        log.warn("Gemini {} for memory model {}; pausing it for {}s", reason, model, delay.toSeconds());
        return new GeminiRetryableException("Gemini " + reason + " for " + model, delay);
    }

    // Gemini puts the wait in error.details[RetryInfo].retryDelay (e.g. "37s" or
    // "12.5s"); fall back to a Retry-After header, then to a default.
    Duration retryDelay(HttpHeaders headers, byte[] body) {
        Duration delay = null;
        try {
            JsonNode details = jsonMapper.readTree(body).path("error").path("details");
            for (JsonNode detail : details) {
                if (detail.path("@type").asText().endsWith("google.rpc.RetryInfo")) {
                    String value = detail.path("retryDelay").asText();
                    if (value.endsWith("s")) {
                        delay = Duration.parse("PT" + value.toUpperCase());
                    }
                }
            }
        } catch (Exception ignored) {
            // Empty or non-JSON body: fall through to the header / default.
        }
        if (delay == null) {
            String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
            try {
                if (retryAfter != null) {
                    delay = Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
                }
            } catch (NumberFormatException ignored) {
                // HTTP-date form: use the default.
            }
        }
        if (delay == null || delay.isNegative() || delay.isZero()) {
            delay = DEFAULT_DELAY;
        }
        // Round sub-second remainders up so we never retry early.
        if (delay.getNano() > 0) {
            delay = Duration.ofSeconds(delay.getSeconds() + 1);
        }
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }
}
