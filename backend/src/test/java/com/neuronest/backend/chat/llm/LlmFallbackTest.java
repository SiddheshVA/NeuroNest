package com.neuronest.backend.chat.llm;

import com.neuronest.backend.chat.SafetyAssessment;
import com.neuronest.backend.chat.SafetyCheckService;
import com.neuronest.backend.chat.SafetyCheckUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real GroqProvider / GeminiProvider / LlmRouter against a local fake HTTP server,
 * covering the fallback rules end to end without API keys.
 */
class LlmFallbackTest {

    /** How a fake endpoint answers. */
    enum Behaviour { OK, RATE_LIMITED, SERVER_ERROR, HANG, DROP_AFTER_FIRST_TOKEN, BAD_JSON, BLOCKED }

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final LlmSettings settings =
            new LlmSettings(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(1));

    private HttpServer server;
    private volatile Behaviour groqBehaviour = Behaviour.OK;
    private volatile Behaviour geminiBehaviour = Behaviour.OK;
    private final AtomicInteger groqCalls = new AtomicInteger();
    private final AtomicInteger geminiCalls = new AtomicInteger();
    private final List<JsonNode> groqRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonNode> geminiRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> groqAuthHeaders = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/groq/", this::handleGroq);
        server.createContext("/gemini/", this::handleGemini);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private GroqProvider groq(String baseUrl) {
        return new GroqProvider("test-groq-key", baseUrl, "llama-3.3-70b-versatile", "llama-3.3-70b-versatile",
                1500, 400, 0.7, "", mapper, settings);
    }

    private GeminiProvider gemini() {
        return new GeminiProvider("test-gemini-key", base() + "/gemini", "gemini-3.6-flash", 8192, 4096, "",
                mapper, settings);
    }

    private LlmRouter router(LlmRouter.Mode mode) {
        return new LlmRouter(groq(base() + "/groq"), gemini(), mode);
    }

    private static String stream(LlmRouter router, List<String> tokens) {
        return router.streamText("reply", "system", List.of(LlmMessage.user("hi")), tokens::add);
    }

    // ---- Streaming reply ----

    @Test
    void groqServesTheReplyWhenHealthy() {
        List<String> tokens = new ArrayList<>();

        String provider = stream(router(LlmRouter.Mode.AUTO), tokens);

        assertThat(provider).isEqualTo("groq");
        assertThat(String.join("", tokens)).isEqualTo("Hello from groq");
        assertThat(geminiCalls).hasValue(0);
        assertThat(groqAuthHeaders).containsOnly("Bearer test-groq-key");
        JsonNode request = groqRequests.getFirst();
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.path("messages").path(0).path("role").asString()).isEqualTo("system");
    }

    @Test
    void groqRateLimitFallsBackToGemini() {
        groqBehaviour = Behaviour.RATE_LIMITED;
        List<String> tokens = new ArrayList<>();

        String provider = stream(router(LlmRouter.Mode.AUTO), tokens);

        assertThat(provider).isEqualTo("gemini");
        assertThat(String.join("", tokens)).isEqualTo("Hello from gemini");
    }

    @Test
    void groqServerErrorFallsBackToGemini() {
        groqBehaviour = Behaviour.SERVER_ERROR;
        assertThat(stream(router(LlmRouter.Mode.AUTO), new ArrayList<>())).isEqualTo("gemini");
    }

    @Test
    void groqTimeoutBeforeFirstTokenFallsBackToGemini() {
        groqBehaviour = Behaviour.HANG;
        long start = System.nanoTime();

        assertThat(stream(router(LlmRouter.Mode.AUTO), new ArrayList<>())).isEqualTo("gemini");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void groqConnectionErrorFallsBackToGemini() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        LlmRouter router = new LlmRouter(groq("http://127.0.0.1:" + closedPort), gemini(), LlmRouter.Mode.AUTO);

        assertThat(stream(router, new ArrayList<>())).isEqualTo("gemini");
    }

    @Test
    void midStreamFailureDoesNotSwitchProviders() {
        groqBehaviour = Behaviour.DROP_AFTER_FIRST_TOKEN;
        List<String> tokens = new ArrayList<>();

        assertThatThrownBy(() -> stream(router(LlmRouter.Mode.AUTO), tokens))
                .isInstanceOfSatisfying(LlmProviderException.class, e -> {
                    assertThat(e.tokensEmitted()).isTrue();
                    assertThat(e.provider()).isEqualTo("groq");
                });
        assertThat(tokens).containsExactly("Hello");
        assertThat(geminiCalls).hasValue(0);
    }

    @Test
    void forcedGeminiNeverCallsGroq() {
        assertThat(stream(router(LlmRouter.Mode.GEMINI), new ArrayList<>())).isEqualTo("gemini");
        assertThat(groqCalls).hasValue(0);
    }

    @Test
    void forcedGroqDoesNotFallBack() {
        groqBehaviour = Behaviour.RATE_LIMITED;

        assertThatThrownBy(() -> stream(router(LlmRouter.Mode.GROQ), new ArrayList<>()))
                .isInstanceOf(LlmUnavailableException.class);
        assertThat(geminiCalls).hasValue(0);
    }

    @Test
    void autoModeWithoutAGroqKeyUsesGeminiOnly() {
        GroqProvider unconfigured = new GroqProvider("", base() + "/groq", "m", "m", 1500, 400, 0.7, "", mapper, settings);
        LlmRouter router = new LlmRouter(unconfigured, gemini(), LlmRouter.Mode.AUTO);

        assertThat(stream(router, new ArrayList<>())).isEqualTo("gemini");
        assertThat(groqCalls).hasValue(0);
    }

    @Test
    void geminiThoughtPartsAreNotStreamedToTheUser() {
        List<String> tokens = new ArrayList<>();
        stream(router(LlmRouter.Mode.GEMINI), tokens);
        assertThat(String.join("", tokens)).doesNotContain("secret reasoning");
    }

    @Test
    void geminiReplyUsesRelaxedSafetySettingsForSupportiveConversation() {
        stream(router(LlmRouter.Mode.GEMINI), new ArrayList<>());

        JsonNode settings = geminiRequests.getFirst().path("safetySettings");
        assertThat(settings.toString())
                .contains("{\"category\":\"HARM_CATEGORY_DANGEROUS_CONTENT\",\"threshold\":\"BLOCK_ONLY_HIGH\"}")
                .contains("{\"category\":\"HARM_CATEGORY_HARASSMENT\",\"threshold\":\"BLOCK_ONLY_HIGH\"}");
    }

    @Test
    void blockedGeminiReplyIsReportedWithItsReasonAndNotRetried() {
        geminiBehaviour = Behaviour.BLOCKED;

        assertThatThrownBy(() -> stream(router(LlmRouter.Mode.GEMINI), new ArrayList<>()))
                .isInstanceOfSatisfying(LlmUnavailableException.class, e ->
                        assertThat(e.getCause().getMessage()).contains("blocked: finishReason=SAFETY"));
        assertThat(geminiCalls).hasValue(1);
    }

    @Test
    void blockedGeminiReplyFallsThroughWhenGroqIsAvailable() {
        // auto mode: Groq first, so a Gemini block only matters when Groq failed; the
        // ordering is Groq -> Gemini, and a blocked fallback ends as "unavailable" (with reason).
        groqBehaviour = Behaviour.SERVER_ERROR;
        geminiBehaviour = Behaviour.BLOCKED;

        assertThatThrownBy(() -> stream(router(LlmRouter.Mode.AUTO), new ArrayList<>()))
                .isInstanceOf(LlmUnavailableException.class);
        assertThat(groqCalls).hasValue(1);
        assertThat(geminiCalls).hasValue(1);
    }

    @Test
    void geminiRateLimitIsNotRetriedSoTheDailyQuotaIsNotBurned() {
        geminiBehaviour = Behaviour.RATE_LIMITED;

        assertThatThrownBy(() -> new SafetyCheckService(router(LlmRouter.Mode.GEMINI), mapper)
                .assess(List.of(), "hello"))
                .isInstanceOf(SafetyCheckUnavailableException.class);
        assertThat(geminiCalls).hasValue(1);
    }

    @Test
    void geminiSafetyCheckCanReadAnyMessageAndReportsPromptBlocks() {
        new SafetyCheckService(router(LlmRouter.Mode.GEMINI), mapper).assess(List.of(), "dieing feels easy");
        assertThat(geminiRequests.getFirst().path("safetySettings").toString())
                .contains("BLOCK_NONE").doesNotContain("BLOCK_ONLY_HIGH");

        geminiBehaviour = Behaviour.BLOCKED;
        assertThatThrownBy(() -> new SafetyCheckService(router(LlmRouter.Mode.GEMINI), mapper)
                .assess(List.of(), "dieing feels easy"))
                .isInstanceOfSatisfying(SafetyCheckUnavailableException.class, e ->
                        assertThat(e.getCause().getCause().getMessage()).contains("prompt blocked: SAFETY"));
    }

    // ---- Usage and committed sinks ----

    /** Holds output like the chat's header splitter: nothing is committed until told so. */
    private static class HoldingSink implements StreamSink {
        final StringBuilder received = new StringBuilder();
        boolean committed;
        int resets;

        @Override public void accept(String text) { received.append(text); }
        @Override public boolean committed() { return committed; }
        @Override public void reset() { received.setLength(0); resets++; }
    }

    @Test
    void streamsReportTokenUsage() {
        LlmRouter.StreamResult groqResult = router(LlmRouter.Mode.GROQ)
                .streamText("reply", "system", List.of(LlmMessage.user("hi")), new HoldingSink());
        assertThat(groqResult.usage()).isEqualTo(new LlmUsage(120, 3));

        LlmRouter.StreamResult geminiResult = router(LlmRouter.Mode.GEMINI)
                .streamText("reply", "system", List.of(LlmMessage.user("hi")), new HoldingSink());
        assertThat(geminiResult.usage()).isEqualTo(new LlmUsage(150, 24));
    }

    @Test
    void uncommittedOutputStillAllowsAProviderSwitch() {
        // Groq sent text (e.g. part of a hidden header) and then dropped: nothing reached the user,
        // so the router resets the sink and Gemini answers instead.
        groqBehaviour = Behaviour.DROP_AFTER_FIRST_TOKEN;
        HoldingSink sink = new HoldingSink();

        LlmRouter.StreamResult result = router(LlmRouter.Mode.AUTO)
                .streamText("reply", "system", List.of(LlmMessage.user("hi")), sink);

        assertThat(result.provider()).isEqualTo("gemini");
        assertThat(sink.resets).isEqualTo(1);
        assertThat(sink.received.toString()).isEqualTo("Hello from gemini");
    }

    @Test
    void committedOutputNeverSwitchesProvider() {
        groqBehaviour = Behaviour.DROP_AFTER_FIRST_TOKEN;
        HoldingSink sink = new HoldingSink() {
            @Override public void accept(String text) { super.accept(text); committed = true; }
        };

        assertThatThrownBy(() -> router(LlmRouter.Mode.AUTO)
                .streamText("reply", "system", List.of(LlmMessage.user("hi")), sink))
                .isInstanceOfSatisfying(LlmProviderException.class, e -> assertThat(e.tokensEmitted()).isTrue());
        assertThat(geminiCalls).hasValue(0);
        assertThat(sink.resets).isZero();
    }

    // ---- Safety check (JSON mode) ----

    @Test
    void safetyCheckUsesGroqJsonMode() {
        SafetyAssessment a = new SafetyCheckService(router(LlmRouter.Mode.AUTO), mapper)
                .assess(List.of(), "mujhe ab jeena nahi hai");

        assertThat(a.riskFlag()).isTrue();
        assertThat(a.crisisResources()).isNotEmpty();
        JsonNode request = groqRequests.getFirst();
        assertThat(request.path("response_format").path("type").asString()).isEqualTo("json_object");
        assertThat(request.path("temperature").asInt()).isZero();
        assertThat(geminiCalls).hasValue(0);
    }

    @Test
    void invalidSafetyJsonFromGroqFallsBackToGeminiJsonMode() {
        groqBehaviour = Behaviour.BAD_JSON;

        SafetyAssessment a = new SafetyCheckService(router(LlmRouter.Mode.AUTO), mapper)
                .assess(List.of(), "mujhe ab jeena nahi hai");

        assertThat(a.riskFlag()).isTrue();
        JsonNode request = geminiRequests.getFirst();
        assertThat(request.path("generationConfig").path("responseMimeType").asString())
                .isEqualTo("application/json");
    }

    @Test
    void safetyCheckFailingOnBothProvidersIsReportedNotSkipped() {
        groqBehaviour = Behaviour.SERVER_ERROR;
        geminiBehaviour = Behaviour.BAD_JSON;

        assertThatThrownBy(() -> new SafetyCheckService(router(LlmRouter.Mode.AUTO), mapper)
                .assess(List.of(), "hello"))
                .isInstanceOf(SafetyCheckUnavailableException.class);
    }

    // ---- Fake endpoints ----

    private static final String CRISIS_JSON = "{\"riskFlag\":true,\"riskReason\":\"passive suicidal ideation\","
            + "\"recommendationTier\":1,\"recommendedAction\":\"seek_help\",\"recommendedInstrument\":null}";

    private void handleGroq(HttpExchange exchange) throws IOException {
        groqCalls.incrementAndGet();
        groqAuthHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
        groqRequests.add(request);
        boolean streaming = request.path("stream").asBoolean(false);

        switch (groqBehaviour) {
            case RATE_LIMITED -> respond(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}");
            case SERVER_ERROR -> respond(exchange, 503, "{\"error\":{\"message\":\"overloaded\"}}");
            case HANG -> hang(exchange);
            case BAD_JSON, BLOCKED -> respond(exchange, 200, groqMessage("this is not json"));
            case DROP_AFTER_FIRST_TOKEN -> {
                startSse(exchange);
                writeSse(exchange, groqDelta("Hello"));
                exchange.close(); // connection ends without [DONE]
            }
            case OK -> {
                if (!streaming) {
                    respond(exchange, 200, groqMessage(CRISIS_JSON));
                    return;
                }
                startSse(exchange);
                writeSse(exchange, "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}");
                for (String token : List.of("Hello", " from", " groq")) {
                    writeSse(exchange, groqDelta(token));
                }
                writeSse(exchange, "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                        + "\"x_groq\":{\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":3}}}");
                writeSse(exchange, "[DONE]");
                exchange.close();
            }
        }
    }

    private void handleGemini(HttpExchange exchange) throws IOException {
        geminiCalls.incrementAndGet();
        JsonNode request = mapper.readTree(exchange.getRequestBody().readAllBytes());
        geminiRequests.add(request);
        boolean streaming = exchange.getRequestURI().getPath().endsWith(":streamGenerateContent");

        switch (geminiBehaviour) {
            case RATE_LIMITED -> respond(exchange, 429, "{}");
            case SERVER_ERROR -> respond(exchange, 503, "{}");
            case HANG -> hang(exchange);
            case BAD_JSON -> respond(exchange, 200, geminiText("not json", true));
            case BLOCKED -> {
                if (!streaming) {
                    respond(exchange, 200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
                    return;
                }
                startSse(exchange);
                writeSse(exchange, "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}");
                exchange.close();
            }
            case DROP_AFTER_FIRST_TOKEN, OK -> {
                if (!streaming) {
                    respond(exchange, 200, geminiText(CRISIS_JSON, true));
                    return;
                }
                startSse(exchange);
                writeSse(exchange, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"secret reasoning\","
                        + "\"thought\":true}]}}]}");
                writeSse(exchange, geminiText("Hello from", false));
                writeSse(exchange, geminiText(" gemini", true).replaceFirst("}$",
                        ",\"usageMetadata\":{\"promptTokenCount\":150,\"candidatesTokenCount\":4,\"thoughtsTokenCount\":20}}"));
                exchange.close();
            }
        }
    }

    private String groqDelta(String token) {
        return mapper.createObjectNode().set("choices", mapper.createArrayNode().add(
                mapper.createObjectNode().set("delta", mapper.createObjectNode().put("content", token)))).toString();
    }

    private String groqMessage(String content) {
        return mapper.createObjectNode().set("choices", mapper.createArrayNode().add(
                mapper.createObjectNode().set("message", mapper.createObjectNode().put("content", content)))).toString();
    }

    private String geminiText(String text, boolean last) {
        var candidate = mapper.createObjectNode();
        candidate.putObject("content").putArray("parts").addObject().put("text", text);
        if (last) {
            candidate.put("finishReason", "STOP");
        }
        return mapper.createObjectNode().set("candidates", mapper.createArrayNode().add(candidate)).toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void startSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }

    private static void writeSse(HttpExchange exchange, String data) throws IOException {
        OutputStream out = exchange.getResponseBody();
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void hang(HttpExchange exchange) {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }
}
