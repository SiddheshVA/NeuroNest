package com.neuronest.backend.chat;

import com.neuronest.backend.safety.CrisisResource;

import java.util.List;

/** A non-streamed chat turn (POST /chat) failed; mapped to HTTP 503 by ApiExceptionHandler. */
public class ChatUnavailableException extends RuntimeException {

    private final ChatTurnError error;
    private final List<CrisisResource> crisisResources;

    public ChatUnavailableException(ChatTurnError error, List<CrisisResource> crisisResources) {
        super(error.userMessage());
        this.error = error;
        this.crisisResources = crisisResources;
    }

    public ChatTurnError error() { return error; }

    public List<CrisisResource> crisisResources() { return crisisResources; }
}
