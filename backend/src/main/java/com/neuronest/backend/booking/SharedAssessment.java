package com.neuronest.backend.booking;

import com.neuronest.backend.assessment.AssessmentResult;

import java.util.Map;

/**
 * The one-time copy of a user's latest assessment result stored on a booking request when they choose
 * to share it (booking_requests.shared_assessment). Exactly these five fields: never the item responses,
 * the risk flag, the session id or anything else. completedAt is ISO-8601 UTC text, for example
 * 2026-10-12T04:30:00Z. Later changes to the user's assessments never change a stored copy.
 */
public record SharedAssessment(String instrument, String instrumentLabel, int score, String severityBand,
                               String completedAt) {

    private static final Map<String, String> LABELS = Map.of(
            "WHO5", "WHO-5",
            "PHQ9", "PHQ-9",
            "GAD7", "GAD-7",
            "PSS10", "PSS-10",
            "K10", "K10");

    static SharedAssessment from(AssessmentResult result) {
        return new SharedAssessment(result.getInstrument(),
                LABELS.getOrDefault(result.getInstrument(), result.getInstrument()),
                result.getScore(), result.getSeverityBand(), result.getCreatedAt().toString());
    }
}
