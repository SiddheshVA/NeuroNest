package com.neuronest.backend.chat.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Picks the provider for each call: Groq first, Gemini as fallback (or one forced
 * provider via llm.force-provider). A call falls back only if the failing provider
 * had not streamed anything yet. Logs which provider served each step — provider
 * names and failure kinds only, never message content.
 */
@Component
public class LlmRouter {

    public enum Mode { AUTO, GROQ, GEMINI }

    /** A result plus the provider that produced it. */
    public record Served<T>(T value, String provider) {}

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final LlmProvider groq;
    private final LlmProvider gemini;
    private final Mode mode;

    @Autowired
    public LlmRouter(GroqProvider groq, GeminiProvider gemini,
                     @Value("${llm.force-provider:auto}") String forceProvider) {
        this((LlmProvider) groq, gemini, parseMode(forceProvider));
    }

    LlmRouter(LlmProvider groq, LlmProvider gemini, Mode mode) {
        this.groq = groq;
        this.gemini = gemini;
        this.mode = mode;

        if (mode == Mode.AUTO && !groq.isConfigured()) {
            log.warn("GROQ_API_KEY is not set: the AI Companion will use Gemini only.");
        } else if (mode == Mode.GROQ && !groq.isConfigured()) {
            log.warn("llm.force-provider=groq but GROQ_API_KEY is not set: chat replies will fail.");
        } else {
            log.info("AI Companion LLM mode: {}", mode.name().toLowerCase(Locale.ROOT));
        }
    }

    static Mode parseMode(String value) {
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("llm.force-provider must be auto, groq or gemini (was: " + value + ")");
        }
    }

    /** Providers to try, in order. */
    List<LlmProvider> chain() {
        return switch (mode) {
            case GROQ -> List.of(groq);
            case GEMINI -> List.of(gemini);
            case AUTO -> {
                List<LlmProvider> providers = new ArrayList<>(2);
                if (groq.isConfigured()) {
                    providers.add(groq);
                }
                providers.add(gemini);
                yield providers;
            }
        };
    }

    /**
     * Non-streamed JSON call. {@code parser} validates the JSON text; if it throws, the
     * response counts as bad and the next provider is tried.
     */
    public <T> Served<T> completeJson(String step, String systemPrompt, List<LlmMessage> messages,
                                      Function<String, T> parser) {
        RuntimeException lastFailure = null;
        for (LlmProvider provider : chain()) {
            try {
                T value = parser.apply(provider.completeJson(systemPrompt, messages));
                logServed(step, provider, lastFailure);
                return new Served<>(value, provider.name());
            } catch (LlmProviderException e) {
                logFailure(step, provider, e.getMessage());
                lastFailure = e;
            } catch (RuntimeException e) {
                logFailure(step, provider, "response failed validation (" + e.getClass().getSimpleName() + ")");
                lastFailure = e;
            }
        }
        throw new LlmUnavailableException(step, lastFailure);
    }

    /** Which provider served a stream, and the token usage it reported (may be null). */
    public record StreamResult(String provider, LlmUsage usage) {}

    /**
     * Streamed text call, shown to the user chunk by chunk. Falls back only while nothing
     * has been streamed.
     *
     * @return the name of the provider that served the reply
     */
    public String streamText(String step, String systemPrompt, List<LlmMessage> messages,
                             Consumer<String> onToken) {
        return streamText(step, systemPrompt, messages, StreamSink.immediate(onToken)).provider();
    }

    /**
     * Streamed text call into a sink that may hold back output. Falls back to the next
     * provider while the sink is uncommitted (resetting it first); once the sink has
     * committed output to the user, a failure is rethrown so the caller can keep what was
     * shown instead of mixing two providers' answers.
     */
    public StreamResult streamText(String step, String systemPrompt, List<LlmMessage> messages, StreamSink sink) {
        LlmProviderException lastFailure = null;
        for (LlmProvider provider : chain()) {
            try {
                LlmUsage usage = provider.streamText(systemPrompt, messages, sink::accept);
                logServed(step, provider, lastFailure);
                return new StreamResult(provider.name(), usage);
            } catch (LlmProviderException e) {
                if (sink.committed()) {
                    log.warn("LLM {} on {} failed mid-stream: {}", step, provider.name(), e.getMessage());
                    throw e.withTokensEmitted(true);
                }
                logFailure(step, provider, e.getMessage());
                sink.reset();
                lastFailure = e;
            }
        }
        throw new LlmUnavailableException(step, lastFailure);
    }

    private static void logServed(String step, LlmProvider provider, RuntimeException earlierFailure) {
        if (earlierFailure == null) {
            log.info("LLM {} served by {}", step, provider.name());
        } else {
            log.info("LLM {} served by {} (fallback)", step, provider.name());
        }
    }

    private static void logFailure(String step, LlmProvider provider, String reason) {
        log.warn("LLM {} on {} failed before any output: {}", step, provider.name(), reason);
    }
}
