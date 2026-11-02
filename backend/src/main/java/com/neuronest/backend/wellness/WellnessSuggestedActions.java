package com.neuronest.backend.wellness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place that decides which next steps a check-in suggests. Only stable codes leave the backend; the
 * frontend maps each code to its own text and link. These are suggestions for self-care and support, never a
 * diagnosis. Computed from the stored band and sleep class each time, so a change here also applies to history.
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

    /** Sleep pattern code to its actions. "none" and an out-of-range sleep result (null) add nothing. */
    static final Map<String, List<Action>> BY_SLEEP_CLASS = Map.of(
            "insomnia_like", List.of(Action.RELAXATION_SLEEP),
            "apnea_like", List.of(Action.SEE_DOCTOR));

    /** Mood actions first, then sleep actions, without duplicates. Unknown values add nothing. */
    public static List<String> codes(String moodBand, String sleepClass) {
        Set<Action> actions = new LinkedHashSet<>();
        if (moodBand != null) actions.addAll(BY_MOOD_BAND.getOrDefault(moodBand, List.of()));
        if (sleepClass != null) actions.addAll(BY_SLEEP_CLASS.getOrDefault(sleepClass, List.of()));
        List<String> codes = new ArrayList<>(actions.size());
        actions.forEach(a -> codes.add(a.name()));
        return List.copyOf(codes);
    }
}
