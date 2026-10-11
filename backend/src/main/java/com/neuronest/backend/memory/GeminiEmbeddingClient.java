package com.neuronest.backend.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;

// Turns a piece of text into a 768-dim embedding via Gemini's embedContent REST API.
// Uses its own RestClient with short timeouts, because recall sits on the chat path.
@Component
public class GeminiEmbeddingClient {

    private final RestClient restClient;
    private final GeminiBackoff backoff;
    private final JsonMapper jsonMapper;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public GeminiEmbeddingClient(@Value("${gemini.api.key}") String apiKey,
                                 MemoryProperties properties,
                                 JsonMapper jsonMapper,
                                 GeminiBackoff backoff) {
        this.backoff = backoff;
        this.apiKey = apiKey;
        this.model = properties.embeddingModel();
        this.dimensions = properties.embeddingDimensions();
        this.jsonMapper = jsonMapper;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.httpConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.embeddingReadTimeout());

        this.restClient = backoff.applyTo(RestClient.builder(), model)
                .baseUrl("https://generativelanguage.googleapis.com")
                .requestFactory(requestFactory)
                .build();
    }

    public float[] embed(String text) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.putObject("content").putArray("parts").addObject().put("text", text);
        body.put("output_dimensionality", dimensions);

        backoff.checkAvailable(model);
        JsonNode response = restClient.post()
                .uri("/v1beta/models/{model}:embedContent", model)
                .header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode values = response == null ? null : response.path("embedding").path("values");
        if (values == null || !values.isArray() || values.size() != dimensions) {
            throw new IllegalStateException("Unexpected embedding response (expected "
                    + dimensions + " values, got " + (values == null ? "none" : values.size()) + ")");
        }

        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) values.get(i).asDouble();
        }
        return vector;
    }
}
