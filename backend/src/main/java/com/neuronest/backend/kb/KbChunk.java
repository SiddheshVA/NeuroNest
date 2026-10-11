package com.neuronest.backend.kb;

/**
 * One retrieved knowledge-base section. {@code content} starts with "Title - Section" (the same
 * text that was embedded); {@code similarity} is the cosine similarity to the search query.
 */
public record KbChunk(String docId, String docTitle, String topic, String section, SectionKind kind,
                      String docType, String safetyLevel, String sourceName, String content, double similarity) {

    /** Stable id for logs: "docId#Section". */
    public String chunkId() {
        return docId + "#" + section;
    }

    public enum SectionKind {
        OVERVIEW, PRACTICAL, SAFETY, PROFESSIONAL, OTHER
    }
}
