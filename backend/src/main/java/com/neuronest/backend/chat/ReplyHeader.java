package com.neuronest.backend.chat;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The verdict line the reply model writes before its reply, e.g.
 * {@code [[RISK:0|TIER:3|INSTR:NONE|ACT:RELAX]]}. Parsed by the backend and never shown.
 * On tier 4 it may end with {@code |PRO:<kind>}; that field is optional and never makes a
 * header unusable.
 */
final class ReplyHeader {

    /** Longest prefix the backend holds while waiting for a header before falling back. */
    static final int MAX_HEADER_CHARS = 100;

    static final String FORMAT = "[[RISK:<0|1>|TIER:<1-5>|INSTR:<PHQ9|GAD7|PSS10|WHO5|NONE>|ACT:<RELAX|RES|PRO|NONE>]]";

    /** The optional last field, written on tier 4 only. */
    static final String PRO_FIELD = "|PRO:<PSYCHIATRIST|PSYCHOLOGIST|COUNSELLOR|NONE>";

    // Anything after ACT (up to "]]") is optional extras: a broken PRO there must not lose the verdict.
    private static final Pattern HEADER = Pattern.compile(
            "\\[\\[\\s*RISK\\s*:\\s*([01])\\s*\\|\\s*TIER\\s*:\\s*([1-5])\\s*\\|\\s*INSTR\\s*:\\s*([A-Z0-9-]+)"
                    + "\\s*\\|\\s*ACT\\s*:\\s*([A-Z]+)\\s*(?:\\|([^\\]]*))?]]",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PRO = Pattern.compile("\\bPRO\\s*:\\s*([A-Z]+)", Pattern.CASE_INSENSITIVE);

    private ReplyHeader() {}

    /** Parses a complete header ("[[...]]"); empty if it doesn't match the format. */
    static Optional<SafetyAssessment> parse(String header) {
        Matcher m = HEADER.matcher(header.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        boolean risk = "1".equals(m.group(1));
        int tier = Integer.parseInt(m.group(2));
        String instrument = m.group(3).toUpperCase(Locale.ROOT);
        String act = m.group(4).toUpperCase(Locale.ROOT);

        String action = switch (tier) {
            case 2 -> SafetyAssessment.ASSESSMENT;
            case 3 -> switch (act) {
                case "RELAX" -> SafetyAssessment.RELAXATION;
                case "RES" -> SafetyAssessment.WELLNESS_RESOURCES;
                default -> SafetyAssessment.CONTINUE;
            };
            case 4 -> SafetyAssessment.FIND_PROFESSIONAL;
            case 5 -> "WHO5".equals(SafetyRules.normaliseInstrument(instrument))
                    ? SafetyAssessment.ASSESSMENT : SafetyAssessment.CONTINUE;
            default -> SafetyAssessment.SEEK_HELP;
        };
        try {
            SafetyAssessment assessment =
                    SafetyRules.normalise(risk, tier, action, instrument, "crisis signal (reply header)");
            return Optional.of(SafetyRules.withProfessionalType(assessment, professionalField(m.group(5))));
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    /** The raw PRO value from the optional extras, or null. */
    private static String professionalField(String extras) {
        if (extras == null) {
            return null;
        }
        Matcher pro = PRO.matcher(extras);
        return pro.find() ? pro.group(1) : null;
    }
}
