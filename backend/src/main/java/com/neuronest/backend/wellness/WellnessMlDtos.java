package com.neuronest.backend.wellness;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

// The ML service's contract for POST /v1/wellness/checkin (ml-service/docs/wellness_models.md). Snake_case on
// the wire. Numbers are typed so they are sent as JSON numbers (the service rejects "4"). Response records
// ignore fields added later by the service instead of failing.
final class WellnessMlDtos {

    private WellnessMlDtos() {}

    /** Exactly the fields the ML service accepts; null optional fields are left out. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MlCheckinRequest(
            @JsonProperty("segment") String segment,
            @JsonProperty("age") Integer age,
            @JsonProperty("sleep_duration") Double sleepDuration,
            @JsonProperty("quality_of_sleep") Integer qualityOfSleep,
            @JsonProperty("stress_level") Integer stressLevel,
            @JsonProperty("physical_activity_minutes") Integer physicalActivityMinutes,
            @JsonProperty("pressure") Integer pressure,
            @JsonProperty("satisfaction") Integer satisfaction,
            @JsonProperty("financial_stress") Integer financialStress,
            @JsonProperty("dietary_habits") String dietaryHabits,
            @JsonProperty("work_study_hours") Double workStudyHours,
            @JsonProperty("family_history") Boolean familyHistory,
            @JsonProperty("bmi_category") String bmiCategory,
            @JsonProperty("heart_rate") Integer heartRate,
            @JsonProperty("daily_steps") Integer dailySteps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlDriver(
            @JsonProperty("feature") String feature,
            @JsonProperty("label") String label,
            @JsonProperty("value") Object value,
            @JsonProperty("direction") String direction,
            @JsonProperty("effect") String effect,
            @JsonProperty("impact") Double impact) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlMood(
            @JsonProperty("model") String model,
            @JsonProperty("segment") String segment,
            @JsonProperty("risk_band") String riskBand,
            @JsonProperty("probability") Double probability,
            @JsonProperty("confidence") String confidence,
            @JsonProperty("confidence_reasons") List<String> confidenceReasons,
            @JsonProperty("drivers") List<MlDriver> drivers,
            @JsonProperty("imputed") List<String> imputed,
            @JsonProperty("ignored_inputs") List<String> ignoredInputs,
            @JsonProperty("out_of_range") List<String> outOfRange) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlGuidanceMessage(@JsonProperty("code") String code, @JsonProperty("text") String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlGuidance(
            @JsonProperty("messages") List<MlGuidanceMessage> messages,
            @JsonProperty("meets_sleep_guideline") Boolean meetsSleepGuideline,
            @JsonProperty("meets_activity_guideline") Boolean meetsActivityGuideline,
            @JsonProperty("weekly_activity_minutes") Double weeklyActivityMinutes,
            @JsonProperty("source") String source) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlSleep(
            @JsonProperty("model") String model,
            @JsonProperty("in_range") Boolean inRange,
            @JsonProperty("reason") String reason,
            @JsonProperty("pattern_code") String patternCode,
            @JsonProperty("display_text") String displayText,
            @JsonProperty("see_doctor") Boolean seeDoctor,
            @JsonProperty("probability") Double probability,
            @JsonProperty("probabilities") Map<String, Double> probabilities,
            @JsonProperty("probabilities_calibrated") Boolean probabilitiesCalibrated,
            @JsonProperty("confidence") String confidence,
            @JsonProperty("confidence_reasons") List<String> confidenceReasons,
            @JsonProperty("drivers") List<MlDriver> drivers,
            @JsonProperty("imputed") List<String> imputed,
            @JsonProperty("out_of_range") List<String> outOfRange,
            @JsonProperty("guidance") MlGuidance guidance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlModelVersion(
            @JsonProperty("version") String version,
            @JsonProperty("trained_at_utc") String trainedAtUtc,
            @JsonProperty("algorithm") String algorithm,
            @JsonProperty("sha256") String sha256) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlCheckinResult(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("mood") MlMood mood,
            @JsonProperty("sleep") MlSleep sleep,
            @JsonProperty("model_versions") Map<String, MlModelVersion> modelVersions,
            @JsonProperty("disclaimer") String disclaimer) {

        /** The parts the backend relies on are present; anything less is treated as the service failing. */
        boolean isComplete() {
            return mood != null && mood.riskBand() != null && mood.probability() != null && mood.confidence() != null
                    && sleep != null && sleep.inRange() != null && sleep.guidance() != null
                    && modelVersions != null && disclaimer != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlErrorField(@JsonProperty("field") String field, @JsonProperty("message") String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlErrorBody(@JsonProperty("code") String code, @JsonProperty("fields") List<MlErrorField> fields) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MlError(@JsonProperty("error") MlErrorBody error) {}
}
