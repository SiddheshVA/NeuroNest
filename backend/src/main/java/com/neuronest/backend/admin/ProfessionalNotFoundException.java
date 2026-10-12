package com.neuronest.backend.admin;

import java.util.UUID;

public class ProfessionalNotFoundException extends RuntimeException {
    public ProfessionalNotFoundException(UUID id) {
        super("Professional not found: " + id);
    }
}
