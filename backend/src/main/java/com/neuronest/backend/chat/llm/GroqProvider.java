package com.neuronest.backend.chat.llm;

import com.neuronest.backend.chat.llm.LlmHttp.Chunk;
import com.neuronest.backend.chat.llm.LlmProviderException.Kind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.function.Consumer;

/**
 * Groq via its OpenAI-compatible Chat Completions API. Primary provider.
 * The API key comes from the GROQ_API_KEY environment variable (groq.api.key).
 */
@Component
public class GroqProvider implements LlmProvider {

    public static final String NAME = "groq";

    private final String apiKey;
    private final String baseUrl;
    private final String replyModel;
    private final String safetyModel;
    private final int replyMaxTokens;
    private final int safetyMaxTokens;
    private final double replyTemperature;
    private final String reasoningEffort;
    private final JsonMapper jsonMapper;
    private final LlmHttp http;

    public GroqProvider(@Value("${groq.api.key:}") String apiKey,
                        @Value("${groq.base-url:https://api.groq.com/openai/v1}") String baseUrl,
                        @Value("${groq.model:llama-3.3-70b-versatile}") String replyModel,
                        @Value("${groq.safety-model:${groq.model:llama-3.3-70b-versatile}}") String safetyModel,
                        @Value("${groq.reply-max-tokens:1500}") int replyMaxTokens,
                        @Value("${groq.safety-max-tokens:400}") int safetyMaxTokens,
                        @Value("${groq.reply-temperature:0.7}") double replyTemperature,
                        // For reasoning models (e.g. openai/gpt-oss-*): low/medium/high; blank = not sent.
                        @Value("${groq.reasoning-effort:}") String reasoningEffort,
                        JsonMapper jsonMapper,
                        LlmSettings settings) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.replyModel = replyModel;
        this.safetyModel = safetyModel;
        this.replyMaxTokens = replyMaxTokens;
        this.safetyMaxTokens = safetyMaxTokens;
        this.replyTemperature = replyTemperature;
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim();
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
        requireConfigured();
        ObjectNode body = baseBody(safetyModel, systemPrompt, messages);
        body.put("temperature", 0);
        body.put("max_completion_tokens", safetyMaxTokens);
        // JSON mode. Groq requires the word "JSON" in the prompt, which the safety prompt has.
        body.putObject("response_format").put("type", "json_object");

        String response = http.postForString(request(body));
        JsonNode content = readTree(response).path("choices").path(0).path("message").path("content");
        if (!content.isString() || content.asString().isBlank()) {
            throw LlmProviderException.badResponse(NAME, "empty response");
        }
        return content.asString();
    }

    @Override
    public LlmUsage streamText(String systemPrompt, List<LlmMessage> messages, Consumer<String> onToken) {
        requireConfigured();
        ObjectNode body = baseBody(replyModel, systemPrompt, messages);
        body.put("temperature", replyTemperature);
        body.put("max_completion_tokens", replyMaxTokens);
        body.put("stream", true);

        return http.stream(request(body), this::parseChunk, onToken);
    }

    private Chunk parseChunk(String data) {
        if ("[DONE]".equals(data)) {
            return Chunk.END;
        }
        JsonNode chunk = readTree(data);
        if (chunk.has("error")) {
            throw new LlmProviderException(NAME, Kind.SERVER_ERROR, null, false, null,
                    LlmProviderException.shorten(chunk.path("error").toString()));
        }
        JsonNode content = chunk.path("choices").path(0).path("delta").path("content");
        // Usage arrives in the last chunk, as x_groq.usage (or OpenAI-style "usage").
        JsonNode usage = chunk.path("x_groq").path("usage");
        if (usage.isMissingNode()) {
            usage = chunk.path("usage");
        }
        LlmUsage reported = usage.isObject()
                ? new LlmUsage(intOrNull(usage, "prompt_tokens"), intOrNull(usage, "completion_tokens"))
                : null;
        return new Chunk(content.isString() ? content.asString() : "", false, reported);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private ObjectNode baseBody(String model, String systemPrompt, List<LlmMessage> messages) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", model);
        if (!reasoningEffort.isEmpty()) {
            // Reasoning models (gpt-oss) would stream their reasoning in delta.reasoning, which is
            // never read; include_reasoning=false stops it being sent at all. (gpt-oss rejects
            // reasoning_format, so that is never sent.)
            body.put("reasoning_effort", reasoningEffort);
            body.put("include_reasoning", false);
        }
        ArrayNode list = body.putArray("messages");
        list.addObject().put("role", "system").put("content", systemPrompt);
        for (LlmMessage message : messages) {
            list.addObject()
                    .put("role", message.role() == LlmMessage.Role.ASSISTANT ? "assistant" : "user")
                    .put("content", message.content());
        }
        return body;
    }

    private HttpRequest request(ObjectNode body) {
        return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
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

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new LlmProviderException(NAME, Kind.CLIENT_ERROR, null);
        }
    }
}
