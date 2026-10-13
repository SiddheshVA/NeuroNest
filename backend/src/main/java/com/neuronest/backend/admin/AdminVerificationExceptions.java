package com.neuronest.backend.admin;

import java.util.List;

public final class AdminVerificationExceptions {

    private AdminVerificationExceptions() {}

    /** 400: the verification checklist has items that are not confirmed. */
    public static class ChecklistIncompleteException extends RuntimeException {
        private final List<String> missing;

        public ChecklistIncompleteException(List<String> missing) {
            super("The verification checklist is not complete. Confirm these items: " + String.join(", ", missing) + ".");
            this.missing = List.copyOf(missing);
        }

        public List<String> missing() {
            return missing;
        }
    }

    /** 404: no such document for this professional, or its file is not in storage. */
    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException() {
            super("Document not found.");
        }
    }
}
