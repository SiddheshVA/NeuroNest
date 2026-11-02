package com.neuronest.backend.wellness;

import com.neuronest.backend.wellness.WellnessDtos.CheckinRequest;
import com.neuronest.backend.wellness.WellnessDtos.FieldError;
import com.neuronest.backend.wellness.WellnessMlDtos.MlCheckinRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps the app's check-in request onto the ML service's request: field by field, so only the allowed fields
 * can ever be sent, with whole-number fields as JSON integers. Ranges and required fields are not checked
 * here; the ML service owns those rules and its 422 comes back as a 400 with field messages.
 */
final class WellnessInputs {

    private WellnessInputs() {}

    static MlCheckinRequest toMl(CheckinRequest r) {
        if (r == null) throw new WellnessExceptions.InvalidInputException(List.of(new FieldError(null, "Request body is missing.")));
        List<FieldError> errors = new ArrayList<>();
        // Names only: the values of unaccepted fields are never echoed, logged or forwarded.
        r.unknownFields().keySet().stream().sorted()
                .forEach(name -> errors.add(new FieldError(name, "is not an accepted field")));
        if (!errors.isEmpty()) throw new WellnessExceptions.InvalidInputException(errors);
        MlCheckinRequest ml = new MlCheckinRequest(
                r.segment(),
                whole("age", r.age(), errors),
                decimal(r.sleepDuration()),
                whole("qualityOfSleep", r.qualityOfSleep(), errors),
                whole("stressLevel", r.stressLevel(), errors),
                whole("physicalActivityMinutes", r.physicalActivityMinutes(), errors),
                whole("pressure", r.pressure(), errors),
                whole("satisfaction", r.satisfaction(), errors),
                whole("financialStress", r.financialStress(), errors),
                r.dietaryHabits(),
                decimal(r.workStudyHours()),
                r.familyHistory(),
                r.bmiCategory(),
                whole("heartRate", r.heartRate(), errors),
                whole("dailySteps", r.dailySteps(), errors));
        if (!errors.isEmpty()) throw new WellnessExceptions.InvalidInputException(errors);
        return ml;
    }

    private static Integer whole(String field, BigDecimal value, List<FieldError> errors) {
        if (value == null) return null;
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            errors.add(new FieldError(field, "must be a whole number"));
            return null;
        }
    }

    private static Double decimal(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    /** The ML service's snake_case field name in this API's camelCase ("sleep_duration" to "sleepDuration"). */
    static String apiFieldName(String mlField) {
        if (mlField == null || mlField.isBlank()) return null;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : mlField.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }
}
