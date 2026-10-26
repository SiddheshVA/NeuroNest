package com.neuronest.backend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Sarvam text-to-speech (POST /text-to-speech, bulbul:v4-flash). The key comes from SARVAM_API_KEY and
 * never leaves the server or reaches the logs; neither does the text. Without the key the app still
 * starts and only spoken replies answer 503 (the browser then falls back to its own voice).
 */
@Component
public class SarvamTtsClient {

    private static final Logger log = LoggerFactory.getLogger(SarvamTtsClient.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final JsonMapper jsonMapper;
    private final HttpClient http;

    public SarvamTtsClient(@Value("${sarvam.api-key:}") String apiKey,
                           @Value("${sarvam.base-url:https://api.sarvam.ai}") String baseUrl,
                           @Value("${sarvam.tts-model:bulbul:v4-flash}") String model,
                           @Value("${sarvam.timeout:PT20S}") Duration timeout,
                           JsonMapper jsonMapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.timeout = timeout;
        this.jsonMapper = jsonMapper;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        log.info(isConfigured() ? "Sarvam text-to-speech is configured."
                : "Sarvam text-to-speech is not configured: spoken replies use the browser's voice.");
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /** MP3 audio for one chunk of at most 2500 characters. */
    public byte[] speak(String text, String languageCode, String speaker) {
        if (!isConfigured()) {
            throw new VoiceExceptions.SpeechUnavailableException();
        }
        ObjectNode body = jsonMapper.createObjectNode()
                .put("text", text)
                .put("language_code", languageCode)
                .put("model", model)
                .put("speaker", speaker)
                .put("pace", 1)
                .put("speech_sample_rate", 24000)
                .put("output_audio_codec", "mp3");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/text-to-speech"))
                .timeout(timeout)
                .header("api-subscription-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                // Sarvam's error body describes the request problem, never the text; only the status is logged.
                log.warn("Sarvam text-to-speech failed with status {}", response.statusCode());
                throw new VoiceExceptions.SpeechUnavailableException();
            }
            JsonNode audio = jsonMapper.readTree(response.body()).path("audios").path(0);
            if (!audio.isString() || audio.asString().isEmpty()) {
                log.warn("Sarvam text-to-speech returned no audio");
                throw new VoiceExceptions.SpeechUnavailableException();
            }
            return Base64.getDecoder().decode(audio.asString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceExceptions.SpeechUnavailableException();
        } catch (VoiceExceptions.SpeechUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Sarvam text-to-speech failed: {}", e.getClass().getName());
            throw new VoiceExceptions.SpeechUnavailableException();
        }
    }
}
