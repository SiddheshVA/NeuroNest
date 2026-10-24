package com.neuronest.backend.chat;

import com.neuronest.backend.professional.CredentialType;
import com.neuronest.backend.safety.CrisisResources;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyHeaderTest {

    private static SafetyAssessment parse(String header) {
        return ReplyHeader.parse(header).orElseThrow();
    }

    @Test
    void parsesEveryTier() {
        assertThat(parse("[[RISK:1|TIER:1|INSTR:NONE|ACT:NONE]]"))
                .isEqualTo(new SafetyAssessment(true, "crisis signal (reply header)", 1, "seek_help", null,
                        CrisisResources.INDIA_RESOURCES));
        assertThat(parse("[[RISK:0|TIER:2|INSTR:PHQ9|ACT:NONE]]"))
                .isEqualTo(new SafetyAssessment(false, null, 2, "assessment", "PHQ9", null));
        assertThat(parse("[[RISK:0|TIER:3|INSTR:NONE|ACT:RELAX]]").recommendedAction()).isEqualTo("relaxation");
        assertThat(parse("[[RISK:0|TIER:3|INSTR:NONE|ACT:RES]]").recommendedAction()).isEqualTo("wellness_resources");
        assertThat(parse("[[RISK:0|TIER:3|INSTR:NONE|ACT:NONE]]").recommendedAction()).isEqualTo("continue");
        assertThat(parse("[[RISK:0|TIER:4|INSTR:GAD7|ACT:PRO]]"))
                .isEqualTo(new SafetyAssessment(false, null, 4, "find_professional", "GAD7", null));
        assertThat(parse("[[RISK:0|TIER:5|INSTR:WHO5|ACT:NONE]]"))
                .isEqualTo(new SafetyAssessment(false, null, 5, "assessment", "WHO5", null));
        assertThat(parse("[[RISK:0|TIER:5|INSTR:NONE|ACT:NONE]]"))
                .isEqualTo(new SafetyAssessment(false, null, 5, "continue", null, null));
    }

    @Test
    void anySignOfCrisisWins() {
        assertThat(parse("[[RISK:0|TIER:1|INSTR:NONE|ACT:NONE]]").riskFlag()).isTrue();
        assertThat(parse("[[RISK:1|TIER:3|INSTR:NONE|ACT:RELAX]]").riskFlag()).isTrue();
    }

    @Test
    void inconsistentFieldsAreNormalisedTowardsTheTierRules() {
        // Tier 2 without a concern instrument -> listen only; tier 3 never gets an assessment.
        assertThat(parse("[[RISK:0|TIER:2|INSTR:NONE|ACT:NONE]]").recommendedAction()).isEqualTo("continue");
        assertThat(parse("[[RISK:0|TIER:3|INSTR:PSS10|ACT:RELAX]]").recommendedInstrument()).isNull();
    }

    @Test
    void toleratesCaseAndSpacing() {
        assertThat(ReplyHeader.parse(" [[ risk:0 | tier:3 | instr:none | act:relax ]] ")).isPresent();
    }

    @Test
    void proIsHonouredOnTierFourAndMappedToTheDirectoryType() {
        assertThat(parse("[[RISK:0|TIER:4|INSTR:NONE|ACT:PRO|PRO:PSYCHIATRIST]]").professionalType())
                .isEqualTo(CredentialType.PSYCHIATRIST.name());
        assertThat(parse("[[RISK:0|TIER:4|INSTR:GAD7|ACT:PRO|PRO:PSYCHOLOGIST]]"))
                .isEqualTo(new SafetyAssessment(false, null, 4, "find_professional", "GAD7", null)
                        .withProfessionalType("PSYCHOLOGIST"));
        // The model writes the Indian spelling; the directory's type is COUNSELOR.
        assertThat(parse("[[RISK:0|TIER:4|INSTR:NONE|ACT:PRO|PRO:COUNSELLOR]]").professionalType())
                .isEqualTo(CredentialType.COUNSELOR.name());
        assertThat(parse("[[ risk:0 | tier:4 | instr:none | act:pro | pro:counselor ]]").professionalType())
                .isEqualTo("COUNSELOR");
    }

    @Test
    void proIsNoneOnEveryOtherTier() {
        assertThat(parse("[[RISK:1|TIER:1|INSTR:NONE|ACT:NONE|PRO:PSYCHIATRIST]]").professionalType()).isNull();
        assertThat(parse("[[RISK:0|TIER:2|INSTR:PHQ9|ACT:NONE|PRO:PSYCHIATRIST]]").professionalType()).isNull();
        assertThat(parse("[[RISK:0|TIER:3|INSTR:NONE|ACT:RELAX|PRO:COUNSELLOR]]").professionalType()).isNull();
        assertThat(parse("[[RISK:0|TIER:5|INSTR:NONE|ACT:NONE|PRO:PSYCHOLOGIST]]").professionalType()).isNull();
        // RISK:1 on a tier 4 header is a crisis: no professional type either.
        assertThat(parse("[[RISK:1|TIER:4|INSTR:NONE|ACT:PRO|PRO:PSYCHIATRIST]]").professionalType()).isNull();
    }

    @Test
    void missingOrInvalidProMeansNoneAndNeverLosesTheVerdict() {
        SafetyAssessment withoutPro = parse("[[RISK:0|TIER:4|INSTR:NONE|ACT:PRO]]");
        assertThat(withoutPro.recommendedAction()).isEqualTo("find_professional");
        assertThat(withoutPro.professionalType()).isNull();
        for (String header : new String[] {
                "[[RISK:0|TIER:4|INSTR:NONE|ACT:PRO|PRO:NONE]]",
                "[[RISK:0|TIER:4|INSTR:NONE|ACT:PRO|PRO:DR SHARMA]]",
                "[[RISK:0|TIER:4|INSTR:NONE|ACT:PRO|PRO:THERAPIST]]",
                "[[RISK:0|TIER:4|INSTR:NONE|ACT:PRO|PRO:]]",
                "[[RISK:0|TIER:4|INSTR:NONE|ACT:PRO|PRO]]",
                "[[RISK:0|TIER:4|INSTR:NONE|ACT:PRO|garbage}{]]"}) {
            SafetyAssessment parsed = parse(header);
            assertThat(parsed.recommendedAction()).as(header).isEqualTo("find_professional");
            assertThat(parsed.professionalType()).as(header).isNull();
        }
    }

    @Test
    void theLongestHeaderFitsTheHoldLimit() {
        assertThat("[[RISK:0|TIER:4|INSTR:PSS10|ACT:PRO|PRO:PSYCHIATRIST]]".length())
                .isLessThan(ReplyHeader.MAX_HEADER_CHARS);
    }

    @Test
    void rejectsMalformedHeaders() {
        assertThat(ReplyHeader.parse("[[RISK:2|TIER:3|INSTR:NONE|ACT:NONE]]")).isEqualTo(Optional.empty());
        assertThat(ReplyHeader.parse("[[RISK:0|TIER:9|INSTR:NONE|ACT:NONE]]")).isEmpty();
        assertThat(ReplyHeader.parse("[[RISK:0|TIER:3|INSTR:NONE]]")).isEmpty();
        assertThat(ReplyHeader.parse("[[RISK:maybe]]")).isEmpty();
        assertThat(ReplyHeader.parse("[[hello]]")).isEmpty();
    }
}
