package com.neuronest.backend.chat;

import com.neuronest.backend.chat.llm.LlmMessage;
import com.neuronest.backend.chat.llm.LlmRouter;
import com.neuronest.backend.chat.llm.StreamSink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The one LLM call per chat turn: a verdict header followed by the reply, streamed into a
 * sink that hides the header (see {@link ChatService}).
 */
@Service
public class ReplyService {

    private final LlmRouter router;
    private final int historyWindow;

    public ReplyService(LlmRouter router, @Value("${chat.history-window:8}") int historyWindow) {
        this.router = router;
        this.historyWindow = historyWindow;
    }

    /**
     * Streams header + reply into {@code sink}. Only the last {@code chat.history-window}
     * messages are sent in full; older ones are covered by {@code summary}.
     *
     * @throws com.neuronest.backend.chat.llm.LlmProviderException if a provider failed after the
     *         sink committed output (no provider switch happens then)
     * @throws com.neuronest.backend.chat.llm.LlmUnavailableException if every provider failed
     *         before the sink committed anything
     * @param knownCrisis the separate safety check already decided this message is a crisis
     */
    public LlmRouter.StreamResult streamReply(List<ChatMessage> history, String userMessage, List<String> memories,
                                              String summary, boolean earlierCrisis, boolean knownCrisis,
                                              StreamSink sink) {
        List<LlmMessage> messages = new ArrayList<>();
        for (ChatMessage message : recent(history)) {
            if (messages.isEmpty() && "assistant".equals(message.getRole())) {
                continue; // the window must open with a user turn
            }
            append(messages, "assistant".equals(message.getRole())
                    ? LlmMessage.assistant(message.getContent())
                    : LlmMessage.user(message.getContent()));
        }
        append(messages, LlmMessage.user(userMessage));

        return router.streamText("reply", ReplyPrompts.systemPrompt(memories, summary, earlierCrisis, knownCrisis),
                messages, sink);
    }

    List<ChatMessage> recent(List<ChatMessage> history) {
        return history.subList(Math.max(0, history.size() - historyWindow), history.size());
    }

    /**
     * Merges consecutive same-role messages (e.g. a user message whose turn failed, followed by
     * the next one) so every provider sees strictly alternating turns.
     */
    static void append(List<LlmMessage> messages, LlmMessage next) {
        if (!messages.isEmpty() && messages.getLast().role() == next.role()) {
            LlmMessage previous = messages.removeLast();
            messages.add(new LlmMessage(next.role(), previous.content() + "\n\n" + next.content()));
        } else {
            messages.add(next);
        }
    }
}
