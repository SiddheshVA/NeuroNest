package com.neuronest.backend.chat;

/** The client's SSE connection is gone (disconnected or timed out); stop producing events. */
public class StreamClosedException extends RuntimeException {

    public StreamClosedException() {
        super("Chat stream closed by the client", null, false, false);
    }
}
