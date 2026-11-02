package com.neuronest.backend.wellness;

import java.util.List;

// Errors of the wellness check-in, mapped by WellnessExceptionHandler. Messages never contain the user's
// answers, results or anything from the ML service beyond field-level validation messages.
public final class WellnessExceptions {

    private WellnessExceptions() {}

    /** Stable error codes in the response body ("code"), for the frontend to branch on. */
    public static final String CODE_UNAVAILABLE = "WELLNESS_UNAVAILABLE";
    public static final String CODE_INVALID_INPUT = "WELLNESS_INVALID_INPUT";

    /** 401: the endpoint needs a signed-in user. */
    public static class NotSignedInException extends RuntimeException {
        public NotSignedInException() {
            super("Sign in to see or manage your wellness check-ins.");
        }
    }

    /** 404: no such check-in, or it belongs to someone else (the two are indistinguishable on purpose). */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException() {
            super("Check-in not found.");
        }
    }

    /**
     * 503: the ML service is unreachable, timed out, failed (5xx), refused the internal key, or answered
     * something unusable. Nothing is saved when this is thrown.
     */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException() {
            super("The wellness check-in is temporarily unavailable. Please try again in a few minutes.");
        }
    }

    /** 400: the answers were rejected (by the backend's type checks or the ML service's validation). */
    public static class InvalidInputException extends RuntimeException {
        private final List<WellnessDtos.FieldError> fields;

        public InvalidInputException(List<WellnessDtos.FieldError> fields) {
            super(summary(fields));
            this.fields = List.copyOf(fields);
        }

        public List<WellnessDtos.FieldError> fields() {
            return fields;
        }

        private static String summary(List<WellnessDtos.FieldError> fields) {
            if (fields.isEmpty()) return "Some check-in answers are invalid.";
            StringBuilder sb = new StringBuilder("Some check-in answers are invalid: ");
            for (int i = 0; i < fields.size(); i++) {
                WellnessDtos.FieldError f = fields.get(i);
                if (i > 0) sb.append("; ");
                sb.append(f.field() == null ? f.message() : f.field() + ": " + f.message());
            }
            return sb.toString();
        }
    }
}
