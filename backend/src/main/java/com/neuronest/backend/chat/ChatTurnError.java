package com.neuronest.backend.chat;

/** Why a chat turn failed, with the friendly message shown to the user. */
public enum ChatTurnError {

    /** The safety check failed on every provider: no reply is generated, helplines are shown. */
    SAFETY_CHECK_FAILED("I'm sorry, I couldn't reply just now. Please try sending your message again "
            + "in a moment. If you need to talk to someone right away, the helplines below are free "
            + "and available 24/7."),

    /** No provider could start the reply. */
    REPLY_FAILED("I couldn't respond just now. Please try sending your message again."),

    /** The reply stopped part-way; the text already shown is kept. */
    STREAM_INTERRUPTED("My reply got cut off. Please try sending your message again."),

    /** The server is handling too many chats right now. */
    BUSY("NeuroNest is very busy right now. Please try again in a moment.");

    private final String userMessage;

    ChatTurnError(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
