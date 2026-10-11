package com.neuronest.backend.chat;

import com.neuronest.backend.auth.AuthUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions/{sessionId}/chat")
public class ChatController {

    private final ChatService chatService;
    private final ThreadPoolTaskExecutor chatStreamExecutor;
    private final Duration streamTimeout;

    public ChatController(ChatService chatService,
                          @Qualifier("chatStreamExecutor") ThreadPoolTaskExecutor chatStreamExecutor,
                          @Value("${chat.stream.timeout:PT3M}") Duration streamTimeout) {
        this.chatService = chatService;
        this.chatStreamExecutor = chatStreamExecutor;
        this.streamTimeout = streamTimeout;
    }

    @PostMapping
    public ChatAiResponse sendMessage(@PathVariable UUID sessionId, @RequestBody Map<String, String> body, Authentication authentication) {
        return chatService.sendMessage(sessionId, body.get("message"), AuthUtil.currentUserId(authentication));
    }

    /**
     * Streams one chat turn as Server-Sent Events: "meta" (safety result, sent before any
     * reply text), then "token" chunks, then "done" — or "error". Session/access/validation
     * problems are rejected before the stream starts, as normal JSON errors.
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@PathVariable UUID sessionId, @RequestBody Map<String, String> body,
                                    Authentication authentication, HttpServletResponse response) {
        ChatService.PreparedTurn turn =
                chatService.prepareTurn(sessionId, body.get("message"), AuthUtil.currentUserId(authentication));

        // Ask proxies not to buffer or transform the stream.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());
        SseChatTurnListener listener = new SseChatTurnListener(emitter);
        try {
            chatStreamExecutor.execute(() -> listener.run(() -> chatService.runTurn(turn, listener)));
        } catch (TaskRejectedException e) {
            listener.onError(ChatTurnError.BUSY, null);
            listener.complete();
        }
        return emitter;
    }

    @GetMapping
    public List<ChatMessage> getHistory(@PathVariable UUID sessionId, Authentication authentication) {
        return chatService.getHistory(sessionId, AuthUtil.currentUserId(authentication));
    }
}
