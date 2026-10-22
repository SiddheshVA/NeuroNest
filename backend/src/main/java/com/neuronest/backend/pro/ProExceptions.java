package com.neuronest.backend.pro;

import java.time.Duration;
import java.util.List;

// Errors specific to the professional area, mapped to responses by ProExceptionHandler. Plain
// validation (400) and conflicts (409) use the app-wide ApiValidationException and ConflictException.
public final class ProExceptions {

    private ProExceptions() {}

    /** A signed-in Clerk user who has not registered as a professional yet. */
    public static class NotRegisteredException extends RuntimeException {
        public NotRegisteredException() {
            super("No professional account exists for this sign-in. Register as a professional first.");
        }
    }

    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException() {
            super("Document not found.");
        }
    }

    /** A missing booking request, or one that belongs to another professional: the same 404 for both. */
    public static class BookingNotFoundException extends RuntimeException {
        public BookingNotFoundException() {
            super("This booking request was not found.");
        }
    }

    public static class FileTooLargeException extends RuntimeException {
        public FileTooLargeException() {
            super("The file is larger than 5 MB. Please upload a smaller file.");
        }
    }

    public static class EmailNotVerifiedException extends RuntimeException {
        public EmailNotVerifiedException() {
            super("Your email address is not verified with Clerk. Verify it from your account settings, then try again.");
        }
    }

    public static class ClerkUnavailableException extends RuntimeException {
        public ClerkUnavailableException() {
            super("Your email address could not be confirmed with Clerk right now. Please try again in a moment.");
        }
    }

    public static class RateLimitedException extends RuntimeException {
        private final Duration retryAfter;

        public RateLimitedException(Duration retryAfter) {
            super("Too many registration attempts from this network. Please try again later.");
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    /** One thing that still has to be provided before submitting for verification. */
    public record MissingItem(String item, String message) {}

    public static class SubmissionIncompleteException extends RuntimeException {
        private final List<MissingItem> missing;

        public SubmissionIncompleteException(List<MissingItem> missing) {
            super("Your profile is not ready to submit. Please complete the missing items.");
            this.missing = List.copyOf(missing);
        }

        public List<MissingItem> missing() {
            return missing;
        }
    }
}
