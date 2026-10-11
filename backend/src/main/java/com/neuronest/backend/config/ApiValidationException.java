package com.neuronest.backend.config;

public class ApiValidationException extends RuntimeException {
    public ApiValidationException(String message) {
        super(message);
    }
}
