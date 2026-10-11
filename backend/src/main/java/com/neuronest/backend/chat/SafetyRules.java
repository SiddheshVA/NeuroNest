package com.neuronest.backend.chat;

import com.neuronest.backend.safety.CrisisResources;

import java.util.Locale;
import java.util.Set;

/**
 * The tier rules of spec Section 4.2a, shared by the single-call reply prompt (reply header)
 * and the separate safety classifier (fallback), plus the normalisation both results go
 * through. Inconsistent answers are always resolved towards safety.
 */
final class SafetyRules {

    /** Tier definitions, checked in the order 1, 4, 2, 3, 5. Each prompt adds its own output format. */
    static final String TIER_DEFINITIONS = """
            Judge ONLY the person's LATEST message. Earlier messages are context that helps you
            understand it, nothing more. The conversation may be in English, Hindi (Devanagari)
            or Hinglish (Hindi in Latin letters mixed with English); read all with equal care.
            Check the tiers in this order: 1, then 4, then 2, then 3, then 5, and stop at the
            first one that applies. (The numbers are fixed labels; they are listed below in the
            order you check them, so tier 4 is checked before tiers 2 and 3.)

            TIER 1 - CRISIS. The latest message shows any sign of suicidal thoughts (passive or
            active), self-harm, intent, a plan or access to means, or intent to harm someone else,
            including indirect wording.
            Examples: "I want to die", "dying feels easy", "I don't want to wake up tomorrow",
            "sab khatam kar dena chahta hoon", "mujhe ab jeena hi nahi hai", "kaash main so jaun aur
            kabhi na uthun", "maine neend ki goliyan jama kar li hain", "khud ko hurt karne ka mann
            karta hai", "मैं मर जाना चाहता हूँ", "मैं खुद को नुकसान पहुँचाना चाहती हूँ", "I'll make him pay, I'll hurt him".
            Use the context to read short answers correctly: "yes" or "haan" to "are you thinking
            of hurting yourself?", "no I'm not safe", or "I have the pills here" after earlier talk
            of dying ARE crisis signals. But a greeting, thanks, small talk or an unrelated message
            after an earlier crisis is NOT a new crisis signal ("hii", "ok", "thank you", "what
            should I eat"): judge it on its own merits. If you are unsure whether the latest
            message itself is a crisis signal, choose tier 1.

            TIER 4 - SIGNIFICANT DISTRESS (without a crisis signal), clear functional impairment
            (can't work, study, sleep, eat or get out of bed), or the person asks for professional
            help (therapist, counsellor, psychologist, psychiatrist, doctor). Any ONE of these is
            enough, and it wins over tiers 2 and 3 even when a specific concern also fits: "I've
            had anxiety for months, can't sleep, I want to see a therapist" is tier 4. Find
            Professional; optionally also PHQ9, GAD7 or PSS10 if one clearly fits.

            TIER 2 - CLEAR, SPECIFIC CONCERN with real signal (lasting over time, or clearly
            affecting the person). It gets the ONE matching assessment:
              persistent low mood, hopelessness, loss of interest -> PHQ9
              ongoing worry, anxiety, nervousness, panic           -> GAD7
              situational stress, feeling overwhelmed by pressure  -> PSS10

            TIER 3 - MILD / SITUATIONAL, no clinical signal (a bad day, one-off frustration,
            tiredness, a small worry). At most the Relaxation Hub or Wellness Resources; never an
            assessment.

            TIER 5 - UNCLEAR. The person hasn't said enough yet (greetings, small talk, vague
            first messages). No recommendation, or WHO5 only if they seem to want a general check
            on how they're doing.
            """;

    private static final Set<String> CONCERN_INSTRUMENTS = Set.of("PHQ9", "GAD7", "PSS10");
    private static final int MAX_REASON_LENGTH = 200;

    private SafetyRules() {}

    /**
     * Turns a raw verdict into a consistent assessment. Any sign of tier 1 makes it a crisis
     * with CrisisResources attached.
     *
     * @throws IllegalStateException if the tier is out of range (the verdict is unusable)
     */
    static SafetyAssessment normalise(boolean riskFlag, int tier, String action, String instrument,
                                      String riskReason) {
        action = action == null ? null : action.trim().toLowerCase(Locale.ROOT);
        instrument = normaliseInstrument(instrument);

        if (riskFlag || tier == 1 || SafetyAssessment.SEEK_HELP.equals(action)) {
            String reason = riskReason == null || riskReason.isBlank() ? "crisis signal" : truncate(riskReason.trim());
            return new SafetyAssessment(true, reason, 1, SafetyAssessment.SEEK_HELP, null,
                    CrisisResources.INDIA_RESOURCES);
        }

        return switch (tier) {
            case 2 -> isConcernInstrument(instrument)
                    ? noRisk(2, SafetyAssessment.ASSESSMENT, instrument)
                    : noRisk(5, SafetyAssessment.CONTINUE, null);
            case 3 -> SafetyAssessment.RELAXATION.equals(action) || SafetyAssessment.WELLNESS_RESOURCES.equals(action)
                    ? noRisk(3, action, null)
                    : noRisk(3, SafetyAssessment.CONTINUE, null);
            case 4 -> noRisk(4, SafetyAssessment.FIND_PROFESSIONAL, isConcernInstrument(instrument) ? instrument : null);
            case 5 -> SafetyAssessment.ASSESSMENT.equals(action) && "WHO5".equals(instrument)
                    ? noRisk(5, SafetyAssessment.ASSESSMENT, "WHO5")
                    : noRisk(5, SafetyAssessment.CONTINUE, null);
            default -> throw new IllegalStateException("safety verdict: tier out of range");
        };
    }

    private static SafetyAssessment noRisk(int tier, String action, String instrument) {
        return new SafetyAssessment(false, null, tier, action, instrument, null);
    }

    // Set.of(...).contains(null) throws, and the instrument is often null.
    private static boolean isConcernInstrument(String instrument) {
        return instrument != null && CONCERN_INSTRUMENTS.contains(instrument);
    }

    /** "PHQ-9", "phq9 " -> "PHQ9"; "NONE"/blank -> null. */
    static String normaliseInstrument(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return cleaned.isEmpty() || "NONE".equals(cleaned) || "NULL".equals(cleaned) ? null : cleaned;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH);
    }
}
