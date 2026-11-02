package com.neuronest.backend.wellness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place that decides which next steps a check-in suggests. Only stable codes leave the backend; the
 * frontend maps each code to its own text and link. These are suggestions for self-care and support, never a
 * diagnosis. Computed from the stored band, sleep class and see-doctor flag each time, so a change here also
 * applies to history.
 */
public final class WellnessSuggestedActions {

    private WellnessSuggestedActions() {}

    public enum Action {
        ASSESSMENT_PHQ9,
        FIND_PROFESSIONAL,
        RELAXATION,
        ASSESSMENT_PSS10,
        KEEP_TRACKING,
        RELAXATION_SLEEP,
        SEE_DOCTOR
    }

    /** Mood risk band (Low / Moderate / High) to its actions, in display order. */
    static final Map<String, List<Action>> BY_MOOD_BAND = Map.of(
            "High", List.of(Action.ASSESSMENT_PHQ9, Action.FIND_PROFESSIONAL),
            "Moderate", List.of(Action.RELAXATION, Action.ASSESSMENT_PSS10),
            "Low", List.of(Action.KEEP_TRACKING));

    /**
     * Sleep pattern code to its self-care actions. SEE_DOCTOR is deliberately not listed here: it comes only
     * from the ML service's see_doctor flag (ml-service display_config.py is the single source of truth).
     */
    static final Map<String, List<Action>> BY_SLEEP_CLASS = Map.of(
            "insomnia_like", List.of(Action.RELAXATION_SLEEP));

    /**
     * Mood actions, then sleep actions, then SEE_DOCTOR when the ML service set see_doctor for an in-range
     * sleep result (sleepClass not null). No duplicates; unknown values add nothing.
     *
     * @param sleepClass     none / insomnia_like / apnea_like, or null when sleep was outside the model's range
     * @param sleepSeeDoctor the ML service's sleep.see_doctor flag (null when out of range)
     */
    public static List<String> codes(String moodBand, String sleepClass, Boolean sleepSeeDoctor) {
        Set<Action> actions = new LinkedHashSet<>();
        if (moodBand != null) actions.addAll(BY_MOOD_BAND.getOrDefault(moodBand, List.of()));
        if (sleepClass != null) {
            actions.addAll(BY_SLEEP_CLASS.getOrDefault(sleepClass, List.of()));
            if (Boolean.TRUE.equals(sleepSeeDoctor)) actions.add(Action.SEE_DOCTOR);
        }
        List<String> codes = new ArrayList<>(actions.size());
        actions.forEach(a -> codes.add(a.name()));
        return List.copyOf(codes);
    }

    /** sleep.see_doctor from a stored (or just received) ML result; null if absent. */
    static Boolean seeDoctorOf(Map<String, Object> result) {
        if (result != null && result.get("sleep") instanceof Map<?, ?> sleep
                && sleep.get("see_doctor") instanceof Boolean flag) {
            return flag;
        }
        return null;
    }
}
