package com.neuronest.backend.professional;

// Thrown for a listing that doesn't exist and for one that exists but isn't publicly visible, with
// the same message, so the response doesn't reveal which.
public class PublicProfessionalNotFoundException extends RuntimeException {
    public PublicProfessionalNotFoundException() {
        super("Professional not found.");
    }
}
