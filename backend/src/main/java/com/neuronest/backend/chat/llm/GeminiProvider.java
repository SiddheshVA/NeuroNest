package com.neuronest.backend.chat.llm;

import com.neuronest.backend.chat.llm.LlmHttp.Chunk;
import com.neuronest.backend.chat.llm.LlmProviderException.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Google Gemini (generateContent / streamGenerateContent). Fallback provider. */
@Component
public class GeminiProvider implements LlmProvider {

    public static final String NAME = "gemini";

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    // Gemini intermittently answers 429 (rate limit) / 5xx (overloaded); retry those briefly.
    private static final int JSON_MAX_ATTEMPTS = 3;
    // A streamed reply is retried once, and only before any text was shown.
    private static final int STREAM_MAX_ATTEMPTS = 2;

    // Supportive conversation about self-harm must not be blocked: replies block only
    // high-probability harmful output in these categories (others keep Gemini's defaults).
    private static final Map<String, String> REPLY_SAFETY_SETTINGS = Map.of(
            "HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_ONLY_HIGH",
            "HARM_CATEGORY_HARASSMENT", "BLOCK_ONLY_HIGH");
    // The safety classifier only returns JSON labels, and must be able to read any message.
    private static final Map<String, String> CLASSIFIER_SAFETY_SETTINGS = Map.of(
            "HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE",
            "HARM_CATEGORY_HARASSMENT", "BLOCK_NONE",
            "HARM_CATEGORY_HATE_SPEECH", "BLOCK_NONE",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int replyMaxTokens;
    private final int safetyMaxTokens;
    private final Double replyTemperature;
    private final String thinkingLevel;
    private final JsonMapper jsonMapper;
    private final LlmHttp http;

    public GeminiProvider(@Value("${gemini.api.key}") String apiKey,
                          @Value("${gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
                          @Value("${gemini.model:gemini-3.8-flash}") String model,
                          // Thinking tokens count towards these limits, so they are generous.
                          @Value("${gemini.reply-max-tokens:8192}") int replyMaxTokens,
                          @Value("${gemini.safety-max-tokens:4096}") int safetyMaxTokens,
                          // Reply call only; blank = model default (Google advises 1.0 for Gemini 3).
                          @Value("${gemini.reply-temperature:}") String replyTemperature,
                          // Optional generationConfig.thinkingConfig.thinkingLevel; blank = model default.
                          @Value("${gemini.thinking-level:}") String thinkingLevel,
                          JsonMapper jsonMapper,
                          LlmSettings settings) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.replyMaxTokens = replyMaxTokens;
        this.safetyMaxTokens = safetyMaxTokens;
        this.replyTemperature = replyTemperature == null || replyTemperature.isBlank()
                ? null : Double.valueOf(replyTemperature.trim());
        this.thinkingLevel = thinkingLevel == null ? "" : thinkingLevel.trim();
        this.jsonMapper = jsonMapper;
        this.http = new LlmHttp(NAME, settings);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public String completeJson(String systemPrompt, List<LlmMessage> messages) {
        // The classifier must be able to read anything the user writes, including self-harm.
        ObjectNode body = baseBody(systemPrompt, messages, safetyMaxTokens, null, CLASSIFIER_SAFETY_SETTINGS);
        ((ObjectNode) body.get("generationConfig")).put("responseMimeType", "application/json");
        HttpRequest request = request(":generateContent", body);

        for (int attempt = 1; ; attempt++) {
            try {
                JsonNode response = readTree(http.postForString(request));
                rejectIfBlocked(response, true);
                String text = candidateText(response);
                if (text.isBlank()) {
                    throw LlmProviderException.badResponse(NAME, "empty response");
                }
                return text;
            } catch (LlmProviderException e) {
                if (!worthRetrying(e) || attempt >= JSON_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Gemini JSON call failed ({}), attempt {}/{}; retrying", e.kind(), attempt, JSON_MAX_ATTEMPTS);
                sleepBeforeRetry(attempt);
            }
        }
    }

    @Override
    public LlmUsage streamText(String systemPrompt, List<LlmMessage> messages, Consumer<String> onToken) {
        HttpRequest request = request(":streamGenerateContent?alt=sse",
                baseBody(systemPrompt, messages, replyMaxTokens, replyTemperature, REPLY_SAFETY_SETTINGS));

        for (int attempt = 1; ; attempt++) {
            try {
                return http.stream(request, this::parseChunk, onToken);
            } catch (LlmProviderException e) {
                if (e.tokensEmitted() || !worthRetrying(e) || attempt >= STREAM_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Gemini stream failed before first token ({}), attempt {}/{}; retrying",
                        e.kind(), attempt, STREAM_MAX_ATTEMPTS);
                sleepBeforeRetry(attempt);
            }
        }
    }

    /**
     * Voice input: one recording sent inline, answered as JSON matching {@code responseSchema}.
     * Separate from the chat calls and outside the router (there is no Groq audio fallback).
     * No sampling parameters are set: this model only accepts its defaults. The audio and the
     * transcript are never logged. Retried once on a 5xx or dropped connection; a 429 or timeout
     * fails at once so the user is not kept waiting.
     */
    public String transcribeAudio(String instruction, byte[] audio, String mimeType, ObjectNode responseSchema,
                                  Duration timeout) {
        ObjectNode root = jsonMapper.createObjectNode();
        // A distressed speaker must be transcribed, whatever they say.
        ArrayNode settings = root.putArray("safetySettings");
        CLASSIFIER_SAFETY_SETTINGS.forEach((category, threshold) ->
                settings.addObject().put("category", category).put("threshold", threshold));
        root.putObject("systemInstruction").putArray("parts").addObject().put("text", instruction);
        ArrayNode parts = root.putArray("contents").addObject().put("role", "user").putArray("parts");
        parts.addObject().putObject("inlineData")
                .put("mimeType", mimeType)
                .put("data", Base64.getEncoder().encodeToString(audio));
        parts.addObject().put("text", "Transcribe this recording.");

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("maxOutputTokens", replyMaxTokens);
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.set("responseSchema", responseSchema);
        if (!thinkingLevel.isEmpty()) {
            generationConfig.putObject("thinkingConfig").put("thinkingLevel", thinkingLevel);
        }
        HttpRequest request = request(":generateContent", root);

        for (int attempt = 1; ; attempt++) {
            try {
                JsonNode response = readTree(http.postForString(request, timeout));
                rejectIfBlocked(response, true);
                String text = candidateText(response);
                if (text.isBlank()) {
                    throw LlmProviderException.badResponse(NAME, "empty transcription response");
                }
                return text;
            } catch (LlmProviderException e) {
                if (!worthRetrying(e) || e.kind() == Kind.TIMEOUT || attempt >= 2) {
                    throw e;
                }
                log.warn("Gemini transcription failed ({}), attempt {}/2; retrying", e.kind(), attempt);
                sleepBeforeRetry(attempt);
            }
        }
    }

    /**
     * 429 is not retried: on the free tier it usually means the daily quota is used up, and
     * every retry spends more of it. The router moves on to the next provider instead.
     */
    private static boolean worthRetrying(LlmProviderException e) {
        return e.isTransient() && e.kind() != Kind.RATE_LIMITED;
    }

    private Chunk parseChunk(String data) {
        JsonNode chunk = readTree(data);
        if (chunk.has("error")) {
            throw LlmProviderException.badResponse(NAME, "error in stream");
        }
        String text = candidateText(chunk);
        if (text.isEmpty()) {
            rejectIfBlocked(chunk, false);
        }
        JsonNode finishReason = chunk.path("candidates").path(0).path("finishReason");
        if ("MAX_TOKENS".equals(finishReason.asString(""))) {
            log.warn("Gemini reply hit maxOutputTokens ({}); the reply was cut short", replyMaxTokens);
        }
        boolean end = !finishReason.isMissingNode();
        return new Chunk(text, end, usageOf(chunk));
    }

    /** usageMetadata is cumulative; completion includes Gemini's thinking tokens. */
    private static LlmUsage usageOf(JsonNode chunk) {
        JsonNode usage = chunk.path("usageMetadata");
        if (!usage.isObject()) {
            return null;
        }
        Integer prompt = usage.path("promptTokenCount").isNumber() ? usage.path("promptTokenCount").asInt() : null;
        int candidates = usage.path("candidatesTokenCount").asInt(0);
        int thoughts = usage.path("thoughtsTokenCount").asInt(0);
        return new LlmUsage(prompt, candidates + thoughts > 0 ? candidates + thoughts : null);
    }

    /**
     * Throws (with the block reason, never content) when Gemini refused the prompt or stopped
     * the candidate for safety-type reasons, so the router can try the other provider.
     */
    private static void rejectIfBlocked(JsonNode response, boolean nonStreamed) {
        JsonNode blockReason = response.path("promptFeedback").path("blockReason");
        if (blockReason.isString()) {
            throw LlmProviderException.badResponse(NAME, "prompt blocked: " + blockReason.asString());
        }
        JsonNode finishReason = response.path("candidates").path(0).path("finishReason");
        if (finishReason.isString() && !"STOP".equals(finishReason.asString())
                && !"MAX_TOKENS".equals(finishReason.asString())) {
            throw LlmProviderException.badResponse(NAME, "blocked: finishReason=" + finishReason.asString());
        }
        if (nonStreamed && response.path("candidates").isMissingNode()) {
            throw LlmProviderException.badResponse(NAME, "no candidates");
        }
    }

    /** Concatenates the text parts of the first candidate, skipping "thought" parts. */
    private static String candidateText(JsonNode response) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            JsonNode value = part.path("text");
            if (value.isString()) {
                text.append(value.asString());
            }
        }
        return text.toString();
    }

    private ObjectNode baseBody(String systemPrompt, List<LlmMessage> messages, int maxOutputTokens,
                                Double temperature, Map<String, String> safetySettings) {
        ObjectNode root = jsonMapper.createObjectNode();
        ArrayNode settings = root.putArray("safetySettings");
        safetySettings.forEach((category, threshold) ->
                settings.addObject().put("category", category).put("threshold", threshold));
        root.putObject("systemInstruction").putArray("parts").addObject().put("text", systemPrompt);

        ArrayNode contents = root.putArray("contents");
        for (LlmMessage message : messages) {
            ObjectNode turn = contents.addObject();
            turn.put("role", message.role() == LlmMessage.Role.ASSISTANT ? "model" : "user");
            turn.putArray("parts").addObject().put("text", message.content());
        }

        ObjectNode generationConfig = root.putObject("generationConfig");
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        if (temperature != null) {
            generationConfig.put("temperature", temperature);
        }
        if (!thinkingLevel.isEmpty()) {
            generationConfig.putObject("thinkingConfig").put("thinkingLevel", thinkingLevel);
        }
        return root;
    }

    private HttpRequest request(String method, ObjectNode body) {
        String path = "/v1beta/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + method;
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(LlmHttp.json(jsonMapper.writeValueAsString(body)))
                .build();
    }

    private JsonNode readTree(String json) {
        try {
            return jsonMapper.readTree(json);
        } catch (RuntimeException e) {
            throw new LlmProviderException(NAME, Kind.BAD_RESPONSE, null, false, e);
        }
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException(NAME, Kind.CONNECTION, null, false, e);
        }
    }
}
