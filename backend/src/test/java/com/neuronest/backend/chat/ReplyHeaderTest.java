package com.neuronest.backend.chat;

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
    void rejectsMalformedHeaders() {
        assertThat(ReplyHeader.parse("[[RISK:2|TIER:3|INSTR:NONE|ACT:NONE]]")).isEqualTo(Optional.empty());
        assertThat(ReplyHeader.parse("[[RISK:0|TIER:9|INSTR:NONE|ACT:NONE]]")).isEmpty();
        assertThat(ReplyHeader.parse("[[RISK:0|TIER:3|INSTR:NONE]]")).isEmpty();
        assertThat(ReplyHeader.parse("[[RISK:maybe]]")).isEmpty();
        assertThat(ReplyHeader.parse("[[hello]]")).isEmpty();
    }
}
