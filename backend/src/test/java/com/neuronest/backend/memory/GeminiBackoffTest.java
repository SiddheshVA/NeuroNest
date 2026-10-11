package com.neuronest.backend.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GeminiBackoffTest {

    static final String RATE_LIMIT_BODY = """
            {"error": {"code": 429, "message": "You exceeded your current quota ... (long text)",
              "status": "RESOURCE_EXHAUSTED",
              "details": [
                {"@type": "type.googleapis.com/google.rpc.QuotaFailure", "violations": []},
                {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "37s"}
              ]}}
            """;

    GeminiBackoff backoff;
    Instant now = Instant.parse("2026-10-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        backoff = new GeminiBackoff(JsonMapper.builder().build());
        backoff.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void readsRetryDelayFromGeminiRetryInfo() {
        assertThat(backoff.retryDelay(new HttpHeaders(), RATE_LIMIT_BODY.getBytes())).isEqualTo(Duration.ofSeconds(37));
    }

    @Test
    void roundsFractionalDelaysUp() {
        String body = """
                {"error": {"details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "12.4s"}]}}
                """;
        assertThat(backoff.retryDelay(new HttpHeaders(), body.getBytes())).isEqualTo(Duration.ofSeconds(13));
    }

    @Test
    void fallsBackToRetryAfterHeaderThenDefault() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "20");
        assertThat(backoff.retryDelay(headers, new byte[0])).isEqualTo(Duration.ofSeconds(20));
        assertThat(backoff.retryDelay(new HttpHeaders(), "not json".getBytes())).isEqualTo(GeminiBackoff.DEFAULT_DELAY);
    }

    @Test
    void doesNotAllowCallsBeforeRetryDelayHasPassed() {
        backoff.onRetryableResponse("m", HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders(), RATE_LIMIT_BODY.getBytes());

        assertThat(backoff.isBlocked("m")).isTrue();
        assertThat(backoff.isBlocked("other-model")).isFalse();
        assertThatThrownBy(() -> backoff.checkAvailable("m")).isInstanceOf(GeminiRetryableException.class);

        backoff.setClock(Clock.fixed(now.plusSeconds(36), ZoneOffset.UTC));
        assertThat(backoff.isBlocked("m")).isTrue();

        backoff.setClock(Clock.fixed(now.plusSeconds(37), ZoneOffset.UTC));
        assertThat(backoff.isBlocked("m")).isFalse();
    }

    @Test
    void restClient429And503BecomeShortRetryableExceptions() {
        RestClient.Builder builder = backoff.applyTo(RestClient.builder(), "m");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/a")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON).body(RATE_LIMIT_BODY));
        server.expect(requestTo("/b")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        RestClient client = builder.build();

        assertThatThrownBy(() -> client.get().uri("/a").retrieve().body(String.class))
                .isInstanceOf(GeminiRetryableException.class)
                .hasMessage("Gemini rate limited (429) for m")
                .satisfies(e -> assertThat(((GeminiRetryableException) e).getRetryAfter()).hasSeconds(37));
        assertThatThrownBy(() -> client.get().uri("/b").retrieve().body(String.class))
                .isInstanceOf(GeminiRetryableException.class)
                .hasMessage("Gemini unavailable (503) for m");
    }
}
