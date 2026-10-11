package com.neuronest.backend.chat.llm;

import java.util.List;
import java.util.function.Consumer;

/**
 * A chat-completion backend (Groq, Gemini). Implementations translate failures
 * into {@link LlmProviderException} so {@link LlmRouter} can decide whether
 * falling back to another provider is safe.
 */
public interface LlmProvider {

    /** Short lowercase name used in logs and llm.force-provider ("groq", "gemini"). */
    String name();

    /** False when the provider has no API key; the router then skips it in auto mode. */
    boolean isConfigured();

    /** Non-streamed completion in the provider's JSON mode. Returns the raw JSON text. */
    String completeJson(String systemPrompt, List<LlmMessage> messages);

    /**
     * Streams a plain-text completion, calling {@code onToken} for each text chunk
     * as it arrives. Returns normally once the stream has finished. Exceptions
     * thrown by {@code onToken} itself propagate unchanged.
     *
     * @return token usage, if the provider reported it (else null)
     */
    LlmUsage streamText(String systemPrompt, List<LlmMessage> messages, Consumer<String> onToken);
}
