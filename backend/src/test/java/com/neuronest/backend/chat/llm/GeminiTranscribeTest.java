package com.neuronest.backend.chat.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GeminiProvider.transcribeAudio against a local fake server: request shape and failure handling. */
class GeminiTranscribeTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final LlmSettings settings =
            new LlmSettings(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(1));
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gemini/", exchange -> {
            calls.incrementAndGet();
            lastRequest.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            String body = status == 200
                    ? "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"text\\\":\\\"hello\\\",\\\"language\\\":\\\"en\\\"}\"}]},"
                    + "\"finishReason\":\"STOP\"}]}"
                    : "{\"error\":{\"message\":\"nope\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GeminiProvider gemini() {
        return new GeminiProvider("test-gemini-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/gemini",
                "gemini-3.6-flash", 8192, 4096, "0.8", "", mapper, settings);
    }

    @Test
    void sendsTheAudioInlineWithAJsonSchemaAndNoSamplingParameters() {
        byte[] audio = {1, 2, 3, 4};

        String json = gemini().transcribeAudio("Transcribe.", audio, "audio/webm",
                mapper.createObjectNode().put("type", "OBJECT"), Duration.ofSeconds(2));

        assertThat(json).isEqualTo("{\"text\":\"hello\",\"language\":\"en\"}");
        JsonNode request = lastRequest.get();
        JsonNode inline = request.path("contents").path(0).path("parts").path(0).path("inlineData");
        assertThat(inline.path("mimeType").asString()).isEqualTo("audio/webm");
        assertThat(Base64.getDecoder().decode(inline.path("data").asString())).containsExactly(audio);
        JsonNode config = request.path("generationConfig");
        assertThat(config.path("responseMimeType").asString()).isEqualTo("application/json");
        assertThat(config.path("responseSchema").path("type").asString()).isEqualTo("OBJECT");
        // The configured reply temperature must not leak into this call.
        assertThat(config.has("temperature")).isFalse();
        assertThat(config.has("topK")).isFalse();
        assertThat(config.has("topP")).isFalse();
        assertThat(config.has("presencePenalty")).isFalse();
        assertThat(config.has("frequencyPenalty")).isFalse();
    }

    @Test
    void rateLimitFailsAtOnceWithoutRetrying() {
        status = 429;

        assertThatThrownBy(() -> gemini().transcribeAudio("Transcribe.", new byte[] {1}, "audio/webm",
                mapper.createObjectNode(), Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(LlmProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(LlmProviderException.Kind.RATE_LIMITED));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void serverErrorIsRetriedOnce() {
        status = 503;

        assertThatThrownBy(() -> gemini().transcribeAudio("Transcribe.", new byte[] {1}, "audio/webm",
                mapper.createObjectNode(), Duration.ofSeconds(2)))
                .isInstanceOf(LlmProviderException.class);
        assertThat(calls.get()).isEqualTo(2);
    }
}
