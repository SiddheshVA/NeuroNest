package com.neuronest.backend.wellness;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Request and response bodies of /api/wellness. Field names follow the app's camelCase convention; "result"
// is the ML service's response exactly as it was received (and stored), so its keys are snake_case.
public final class WellnessDtos {

    private WellnessDtos() {}

    /**
     * POST /api/wellness/checkin. Any other field (gender, city, the suicidal-thoughts question, ...) lands in
     * unknownFields and the request is rejected with a 400 naming it (WellnessInputs), so nothing outside this
     * list is ever accepted or sent on. The app's mapper ignores unknown properties globally, hence the explicit
     * collection. Whole-number fields are BigDecimal here only so that 4.5 is reported as an error instead of
     * being silently truncated; WellnessInputs sends them as JSON integers.
     * sleepDuration is hours per night; physicalActivityMinutes is minutes per day.
     */
    public record CheckinRequest(
            String segment,
            BigDecimal age,
            BigDecimal sleepDuration,
            BigDecimal qualityOfSleep,
            BigDecimal stressLevel,
            BigDecimal physicalActivityMinutes,
            BigDecimal pressure,
            BigDecimal satisfaction,
            BigDecimal financialStress,
            String dietaryHabits,
            BigDecimal workStudyHours,
            Boolean familyHistory,
            String bmiCategory,
            BigDecimal heartRate,
            BigDecimal dailySteps,
            @JsonAnySetter Map<String, Object> unknownFields) {

        public CheckinRequest {
            unknownFields = unknownFields == null ? Map.of() : unknownFields;
        }
    }

    /**
     * The check-in answer. id and createdAt are null and saved is false for an anonymous check-in (nothing is
     * stored). suggestedActions are stable codes (WellnessSuggestedActions) for the frontend to map, never
     * text, links or a diagnosis.
     */
    public record CheckinResponse(UUID id, boolean saved, Instant createdAt, Map<String, Object> result,
                                  List<String> suggestedActions) {}

    /** One stored check-in in the owner's history. */
    public record CheckinItem(UUID id, Instant createdAt, String segment, Map<String, Object> result,
                              List<String> suggestedActions) {}

    public record CheckinPage(List<CheckinItem> items, int page, int size, long totalElements, int totalPages) {}

    /** A field the ML service (or the backend) rejected; field uses this API's camelCase names. */
    public record FieldError(String field, String message) {}
}
