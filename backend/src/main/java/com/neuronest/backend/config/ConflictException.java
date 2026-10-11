package com.neuronest.backend.config;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
