package com.neuronest.backend.wellness;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The suggested-action mapping and the request mapping, without Spring. */
class WellnessSuggestedActionsTest {

    @Test
    void moodBandsAndSleepClassesMapToTheReviewedCodes() {
        assertThat(WellnessSuggestedActions.codes("High", null)).containsExactly("ASSESSMENT_PHQ9", "FIND_PROFESSIONAL");
        assertThat(WellnessSuggestedActions.codes("Moderate", null)).containsExactly("RELAXATION", "ASSESSMENT_PSS10");
        assertThat(WellnessSuggestedActions.codes("Low", null)).containsExactly("KEEP_TRACKING");
        assertThat(WellnessSuggestedActions.codes("Low", "insomnia_like")).containsExactly("KEEP_TRACKING", "RELAXATION_SLEEP");
        assertThat(WellnessSuggestedActions.codes("Moderate", "apnea_like"))
                .containsExactly("RELAXATION", "ASSESSMENT_PSS10", "SEE_DOCTOR");
        assertThat(WellnessSuggestedActions.codes("Low", "none")).containsExactly("KEEP_TRACKING");
        assertThat(WellnessSuggestedActions.codes("Unknown", "Insomnia")).isEmpty();
        assertThat(WellnessSuggestedActions.codes(null, null)).isEmpty();
    }

    @Test
    void everyCodeIsAStableEnumNameNeverText() {
        for (var band : new String[]{"Low", "Moderate", "High"}) {
            for (var sleep : new String[]{null, "none", "insomnia_like", "apnea_like"}) {
                assertThat(WellnessSuggestedActions.codes(band, sleep))
                        .allSatisfy(code -> assertThat(code).matches("[A-Z0-9_]+"));
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
