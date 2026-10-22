package com.neuronest.backend.booking;

// Errors specific to bookings, mapped by BookingExceptionHandler. Plain validation (400) and conflicts
// (409) use the app-wide ApiValidationException and ConflictException.
public final class BookingExceptions {

    private BookingExceptions() {}

    /** 404: a missing booking, someone else's booking, or a professional that is not publicly listed. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 429: too many new requests in the last 24 hours. */
    public static class TooManyRequestsException extends RuntimeException {
        public TooManyRequestsException(String message) {
            super(message);
        }
    }

    /** 401: the signed-in Clerk user has no users row (should not happen once the filter has run). */
    public static class NotSignedInException extends RuntimeException {
        public NotSignedInException() {
            super("Please sign in to continue.");
        }
    }
}
