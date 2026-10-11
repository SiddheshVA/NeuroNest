package com.neuronest.backend.session;

public class SessionAccessDeniedException extends RuntimeException {
    public SessionAccessDeniedException(String message) {
        super(message);
    }
}