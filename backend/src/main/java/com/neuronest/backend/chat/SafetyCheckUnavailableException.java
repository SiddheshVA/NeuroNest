package com.neuronest.backend.chat;

/** The safety check could not be completed on any provider. The turn must not proceed to a reply. */
public class SafetyCheckUnavailableException extends RuntimeException {

    public SafetyCheckUnavailableException(Throwable cause) {
        super("Safety check unavailable on every provider", cause);
    }
}
