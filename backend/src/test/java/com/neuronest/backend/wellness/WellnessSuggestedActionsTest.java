package com.neuronest.backend.wellness;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The suggested-action mapping and the request mapping, without Spring. */
class WellnessSuggestedActionsTest {

    @Test
    void moodBandsAndSleepClassesMapToTheReviewedCodes() {
        assertThat(WellnessSuggestedActions.codes("High", null, null)).containsExactly("ASSESSMENT_PHQ9", "FIND_PROFESSIONAL");
        assertThat(WellnessSuggestedActions.codes("Moderate", null, null)).containsExactly("RELAXATION", "ASSESSMENT_PSS10");
        assertThat(WellnessSuggestedActions.codes("Low", null, null)).containsExactly("KEEP_TRACKING");
        // As ml-service flags them today: insomnia_like and apnea_like see_doctor=true, none false.
        assertThat(WellnessSuggestedActions.codes("Low", "insomnia_like", true))
                .containsExactly("KEEP_TRACKING", "RELAXATION_SLEEP", "SEE_DOCTOR");
        assertThat(WellnessSuggestedActions.codes("Moderate", "apnea_like", true))
                .containsExactly("RELAXATION", "ASSESSMENT_PSS10", "SEE_DOCTOR");
        assertThat(WellnessSuggestedActions.codes("Low", "none", false)).containsExactly("KEEP_TRACKING");
        assertThat(WellnessSuggestedActions.codes("Unknown", "Insomnia", null)).isEmpty();
        assertThat(WellnessSuggestedActions.codes(null, null, null)).isEmpty();
    }

    @Test
    void seeDoctorFollowsTheMlFlagNotTheSleepClass() {
        assertThat(WellnessSuggestedActions.codes("Low", "insomnia_like", false))
                .as("flag off: self-care only").containsExactly("KEEP_TRACKING", "RELAXATION_SLEEP");
        assertThat(WellnessSuggestedActions.codes("Low", "apnea_like", false)).containsExactly("KEEP_TRACKING");
        assertThat(WellnessSuggestedActions.codes("Low", "none", true))
                .as("flag on for any class").containsExactly("KEEP_TRACKING", "SEE_DOCTOR");
        assertThat(WellnessSuggestedActions.codes("Low", "apnea_like", null)).containsExactly("KEEP_TRACKING");
        assertThat(WellnessSuggestedActions.codes("High", null, true))
                .as("out-of-range sleep never suggests a doctor visit").containsExactly("ASSESSMENT_PHQ9", "FIND_PROFESSIONAL");
    }

    @Test
    void theFlagIsReadFromAStoredResult() {
        assertThat(WellnessSuggestedActions.seeDoctorOf(java.util.Map.of("sleep", java.util.Map.of("see_doctor", true)))).isTrue();
        assertThat(WellnessSuggestedActions.seeDoctorOf(java.util.Map.of("sleep", java.util.Map.of("see_doctor", false)))).isFalse();
        var nullFlag = new java.util.HashMap<String, Object>();
        nullFlag.put("see_doctor", null);
        assertThat(WellnessSuggestedActions.seeDoctorOf(java.util.Map.of("sleep", nullFlag))).isNull();
        assertThat(WellnessSuggestedActions.seeDoctorOf(java.util.Map.of())).isNull();
        assertThat(WellnessSuggestedActions.seeDoctorOf(null)).isNull();
    }

    @Test
    void everyCodeIsAStableEnumNameNeverText() {
        for (var band : new String[]{"Low", "Moderate", "High"}) {
            for (var sleep : new String[]{null, "none", "insomnia_like", "apnea_like"}) {
                for (var flag : new Boolean[]{null, false, true}) {
                    assertThat(WellnessSuggestedActions.codes(band, sleep, flag))
                            .allSatisfy(code -> assertThat(code).matches("[A-Z0-9_]+"));
                }
            }
        }
    }

    @Test
    void wholeNumberFieldsMustBeWholeAndOptionalOnesAreLeftOut() {
        var ok = WellnessInputs.toMl(WellnessTestData.request());
        assertThat(ok.age()).isEqualTo(34);
        assertThat(ok.sleepDuration()).isEqualTo(6.5);
        assertThat(ok.familyHistory()).isNull();

        var r = WellnessTestData.request();
        var fractional = new WellnessDtos.CheckinRequest(r.segment(), new BigDecimal("34.5"), r.sleepDuration(),
                r.qualityOfSleep(), r.stressLevel(), r.physicalActivityMinutes(), new BigDecimal("4.2"), r.satisfaction(),
                r.financialStress(), r.dietaryHabits(), r.workStudyHours(), r.familyHistory(), r.bmiCategory(),
                r.heartRate(), r.dailySteps(), null);
        assertThatThrownBy(() -> WellnessInputs.toMl(fractional))
                .isInstanceOfSatisfying(WellnessExceptions.InvalidInputException.class, e -> assertThat(e.fields())
                        .extracting(WellnessDtos.FieldError::field).containsExactly("age", "pressure"));
        assertThat(WellnessInputs.toMl(new WellnessDtos.CheckinRequest(r.segment(), new BigDecimal("34.0"),
                r.sleepDuration(), r.qualityOfSleep(), r.stressLevel(), r.physicalActivityMinutes(), r.pressure(),
                r.satisfaction(), r.financialStress(), null, null, null, null, null, null, null)).age()).isEqualTo(34);
        assertThatThrownBy(() -> WellnessInputs.toMl(null)).isInstanceOf(WellnessExceptions.InvalidInputException.class);
    }

    @Test
    void mlFieldNamesAreReportedInThisApisNames() {
        assertThat(WellnessInputs.apiFieldName("sleep_duration")).isEqualTo("sleepDuration");
        assertThat(WellnessInputs.apiFieldName("physical_activity_minutes")).isEqualTo("physicalActivityMinutes");
        assertThat(WellnessInputs.apiFieldName("age")).isEqualTo("age");
        assertThat(WellnessInputs.apiFieldName(null)).isNull();
    }
}
