package com.neuronest.backend.chat;

import com.neuronest.backend.safety.CrisisResource;
import com.neuronest.backend.safety.CrisisResources;

import java.util.List;

/**
 * Result of the per-turn safety/recommendation check (step A), sent to the client
 * as the SSE "meta" event before any reply text.
 *
 * <ul>
 *   <li>{@code riskFlag}: THIS message carries a crisis signal (full alert).</li>
 *   <li>{@code earlierRiskInConversation}: an earlier message in the same conversation was
 *       risk-flagged (calm banner, gentle check-in). Worked out from saved messages, not by
 *       the classifier.</li>
 *   <li>{@code crisisResources}: set when either is true, always from CrisisResources.</li>
 * </ul>
 */
public record SafetyAssessment(
        boolean riskFlag,
        String riskReason,
        int recommendationTier,
        String recommendedAction,
        String recommendedInstrument,
        List<CrisisResource> crisisResources,
        boolean earlierRiskInConversation
) {
    public static final String SEEK_HELP = "seek_help";
    public static final String ASSESSMENT = "assessment";
    public static final String RELAXATION = "relaxation";
    public static final String WELLNESS_RESOURCES = "wellness_resources";
    public static final String FIND_PROFESSIONAL = "find_professional";
    public static final String CONTINUE = "continue";

    /** Classifier result for the latest message alone (no earlier risk known yet). */
    public SafetyAssessment(boolean riskFlag, String riskReason, int recommendationTier,
                            String recommendedAction, String recommendedInstrument,
                            List<CrisisResource> crisisResources) {
        this(riskFlag, riskReason, recommendationTier, recommendedAction, recommendedInstrument,
                crisisResources, false);
    }

    /** Copy noting whether an earlier message in this conversation was risk-flagged. */
    public SafetyAssessment withEarlierRisk(boolean earlierRisk) {
        if (earlierRisk == earlierRiskInConversation) {
            return this;
        }
        List<CrisisResource> resources = riskFlag || earlierRisk ? CrisisResources.INDIA_RESOURCES : null;
        return new SafetyAssessment(riskFlag, riskReason, recommendationTier, recommendedAction,
                recommendedInstrument, resources, earlierRisk);
    }
}
