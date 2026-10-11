package com.neuronest.backend.assessment;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Gad7ScoringServiceTest {

    private final Gad7ScoringService service = new Gad7ScoringService();

    // Distributes rawSum across the 7 items (each capped 0-3).
    private static List<Integer> responses(int rawSum) {
        Integer[] arr = new Integer[7];
        int remaining = rawSum;
        for (int i = 0; i < 7; i++) {
            int v = Math.min(3, remaining);
            arr[i] = v;
            remaining -= v;
        }
        return List.of(arr);
    }

    @Test
    void allZeroResponses_isMinimal_noRisk() {
        ScoreResult result = service.score(responses(0));
        assertEquals(0, result.score());
        assertEquals("minimal", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void allMaxResponses_isSevere_withRisk() {
        ScoreResult result = service.score(responses(21));
        assertEquals(21, result.score());
        assertEquals("severe", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void boundary_minimal_upperEdge() {
        ScoreResult result = service.score(responses(4));
        assertEquals("minimal", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_mild_lowerEdge() {
        ScoreResult result = service.score(responses(5));
        assertEquals("mild", result.severityBand());
    }

    @Test
    void boundary_mild_upperEdge() {
        ScoreResult result = service.score(responses(9));
        assertEquals("mild", result.severityBand());
    }

    @Test
    void boundary_moderate_lowerEdge() {
        ScoreResult result = service.score(responses(10));
        assertEquals("moderate", result.severityBand());
    }

    @Test
    void boundary_moderate_upperEdge_noRiskYet() {
        ScoreResult result = service.score(responses(14));
        assertEquals(14, result.score());
        assertEquals("moderate", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_severe_lowerEdge_flagsRisk() {
        ScoreResult result = service.score(responses(15));
        assertEquals(15, result.score());
        assertEquals("severe", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void wrongNumberOfResponses_throws() {
        assertThrows(ResponseStatusException.class, () -> service.score(responses(4).subList(0, 6)));
        List<Integer> tooMany = new ArrayList<>(responses(4));
        tooMany.add(0);
        assertThrows(ResponseStatusException.class, () -> service.score(tooMany));
        assertThrows(ResponseStatusException.class, () -> service.score(null));
    }

    @Test
    void outOfRangeResponse_throws() {
        List<Integer> tooHigh = new ArrayList<>(responses(0));
        tooHigh.set(0, 4);
        assertThrows(ResponseStatusException.class, () -> service.score(tooHigh));

        List<Integer> negative = new ArrayList<>(responses(0));
        negative.set(0, -1);
        assertThrows(ResponseStatusException.class, () -> service.score(negative));
    }
}
