package com.neuronest.backend.assessment;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phq9ScoringServiceTest {

    private final Phq9ScoringService service = new Phq9ScoringService();

    // Distributes sumFirstEight across items 1-8 (each capped 0-3) and fixes
    // item 9 (suicidal ideation, index 8) to the given value.
    private static List<Integer> responses(int sumFirstEight, int item9) {
        Integer[] arr = new Integer[9];
        int remaining = sumFirstEight;
        for (int i = 0; i < 8; i++) {
            int v = Math.min(3, remaining);
            arr[i] = v;
            remaining -= v;
        }
        arr[8] = item9;
        return List.of(arr);
    }

    @Test
    void allZeroResponses_isMinimal_noRisk() {
        ScoreResult result = service.score(responses(0, 0));
        assertEquals(0, result.score());
        assertEquals("minimal", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void allMaxResponses_isSevere_withRisk() {
        ScoreResult result = service.score(responses(24, 3));
        assertEquals(27, result.score());
        assertEquals("severe", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void boundary_minimal_upperEdge() {
        ScoreResult result = service.score(responses(4, 0));
        assertEquals(4, result.score());
        assertEquals("minimal", result.severityBand());
    }

    @Test
    void boundary_mild_lowerEdge() {
        ScoreResult result = service.score(responses(5, 0));
        assertEquals(5, result.score());
        assertEquals("mild", result.severityBand());
    }

    @Test
    void boundary_mild_upperEdge() {
        ScoreResult result = service.score(responses(9, 0));
        assertEquals(9, result.score());
        assertEquals("mild", result.severityBand());
    }

    @Test
    void boundary_moderate_lowerEdge() {
        ScoreResult result = service.score(responses(10, 0));
        assertEquals(10, result.score());
        assertEquals("moderate", result.severityBand());
    }

    @Test
    void boundary_moderate_upperEdge() {
        ScoreResult result = service.score(responses(14, 0));
        assertEquals(14, result.score());
        assertEquals("moderate", result.severityBand());
    }

    @Test
    void boundary_moderatelySevere_lowerEdge() {
        ScoreResult result = service.score(responses(15, 0));
        assertEquals(15, result.score());
        assertEquals("moderately severe", result.severityBand());
    }

    @Test
    void boundary_moderatelySevere_upperEdge_noRiskWithoutItem9() {
        ScoreResult result = service.score(responses(19, 0));
        assertEquals(19, result.score());
        assertEquals("moderately severe", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_severe_lowerEdge_risksByTotalAlone() {
        ScoreResult result = service.score(responses(20, 0));
        assertEquals(20, result.score());
        assertEquals("severe", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void item9NonZero_flagsRisk_evenWithLowTotal() {
        // Clinical safety rule: any endorsement of item 9 (suicidal ideation,
        // index 8) must flag risk regardless of how low the total score is.
        // This override already exists in Phq9ScoringService (line 36-37):
        //   boolean suicidalIdeation = responses.get(8) >= 1;
        //   boolean riskFlag = rawScore >= 20 || suicidalIdeation;
        ScoreResult result = service.score(responses(0, 1));
        assertEquals(1, result.score());
        assertEquals("minimal", result.severityBand());
        assertTrue(result.riskFlag(), "item 9 >= 1 must flag risk even in the 'minimal' band");
    }

    @Test
    void item9AtMax_flagsRisk_withLowTotal() {
        ScoreResult result = service.score(responses(1, 3));
        assertEquals(4, result.score());
        assertEquals("minimal", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void wrongNumberOfResponses_throws() {
        assertThrows(ResponseStatusException.class, () -> service.score(responses(4, 0).subList(0, 8)));
        List<Integer> tooMany = new java.util.ArrayList<>(responses(4, 0));
        tooMany.add(0);
        assertThrows(ResponseStatusException.class, () -> service.score(tooMany));
        assertThrows(ResponseStatusException.class, () -> service.score(null));
    }

    @Test
    void outOfRangeResponse_throws() {
        assertThrows(ResponseStatusException.class, () -> service.score(responses(0, 4)));
        List<Integer> negative = new java.util.ArrayList<>(responses(0, 0));
        negative.set(0, -1);
        assertThrows(ResponseStatusException.class, () -> service.score(negative));
    }
}
