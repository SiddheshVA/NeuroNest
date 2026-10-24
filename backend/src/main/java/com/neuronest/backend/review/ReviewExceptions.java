package com.neuronest.backend.review;

// Errors specific to reviews, mapped by ReviewExceptionHandler (and by the admin and professional handlers
// for their own controllers). Plain validation (400) and conflicts (409) use the app-wide
// ApiValidationException and ConflictException.
public final class ReviewExceptions {

    private ReviewExceptions() {}

    /** 404: a missing booking or review, someone else's, or a professional that is not publicly listed. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
