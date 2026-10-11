package com.neuronest.backend.chat;

import com.neuronest.backend.safety.CrisisResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a chat turn to the client as Server-Sent Events:
 * {@code meta} -> {@code token}* -> {@code done}, or {@code error}. Each event's data is JSON.
 */
final class SseChatTurnListener implements ChatTurnListener {

    private static final Logger log = LoggerFactory.getLogger(SseChatTurnListener.class);

    private final SseEmitter emitter;
    private volatile boolean closed;

    SseChatTurnListener(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onTimeout(() -> closed = true);
        emitter.onError(error -> closed = true);
    }

    /** Runs the turn on the current (worker) thread and always completes the stream. */
    void run(Runnable turn) {
        try {
            turn.run();
        } catch (StreamClosedException e) {
            // Client went away; nothing left to send.
        } catch (RuntimeException e) {
            log.error("Chat stream failed unexpectedly", e);
            onError(ChatTurnError.REPLY_FAILED, null);
        } finally {
            complete();
        }
    }

    @Override
    public void onMeta(SafetyAssessment assessment) {
        send("meta", assessment);
    }

    @Override
    public void onToken(String text) {
        send("token", Map.of("text", text));
    }

    @Override
    public void onDone(UUID assistantMessageId) {
        send("done", Map.of("messageId", assistantMessageId.toString()));
    }

    @Override
    public void onError(ChatTurnError error, List<CrisisResource> crisisResources) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", error.name());
        data.put("message", error.userMessage());
        data.put("crisisResources", crisisResources);
        try {
            send("error", data);
        } catch (StreamClosedException e) {
            // Nobody left to tell.
        }
    }

    void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            // Already completed or timed out.
        }
    }

    private void send(String event, Object data) {
        if (closed) {
            throw new StreamClosedException();
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            closed = true;
            throw new StreamClosedException();
        }
    }
}
