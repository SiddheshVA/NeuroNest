package com.neuronest.backend.assessment;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Who5ScoringServiceTest {

    private final Who5ScoringService service = new Who5ScoringService();

    // Distributes rawSum across the 5 items (each capped at 0-5).
    private static List<Integer> responses(int rawSum) {
        Integer[] arr = new Integer[5];
        int remaining = rawSum;
        for (int i = 0; i < 5; i++) {
            int v = Math.min(5, remaining);
            arr[i] = v;
            remaining -= v;
        }
        return List.of(arr);
    }

    @Test
    void allZeroResponses_isHighestRisk_notLowestRisk() {
        // WHO-5 is a wellbeing scale: LOW score means poor wellbeing, so the
        // all-zero response set is the worst case, not the best/baseline case
        // (unlike PHQ-9/GAD-7/PSS-10/K10, where a low score is healthy).
        ScoreResult result = service.score(responses(0));
        assertEquals(0, result.score());
        assertEquals("moderate-to-severe", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void allMaxResponses_isBestCase() {
        ScoreResult result = service.score(responses(25));
        assertEquals(100, result.score());
        assertEquals("good", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_moderateToSevere_upperEdge() {
        // rawScore 7 -> finalScore 28, just under the "mild concern" cutoff of 29
        ScoreResult result = service.score(responses(7));
        assertEquals(28, result.score());
        assertEquals("moderate-to-severe", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void boundary_mildConcern_lowerEdge() {
        // rawScore 8 -> finalScore 32, first value in "mild concern"
        ScoreResult result = service.score(responses(8));
        assertEquals(32, result.score());
        assertEquals("mild concern", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_mildConcern_upperEdge() {
        // rawScore 12 -> finalScore 48, last value still in "mild concern"
        ScoreResult result = service.score(responses(12));
        assertEquals(48, result.score());
        assertEquals("mild concern", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_good_lowerEdge() {
        // rawScore 13 -> finalScore 52, first value in "good"
        ScoreResult result = service.score(responses(13));
        assertEquals(52, result.score());
        assertEquals("good", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void wrongNumberOfResponses_throws() {
        assertThrows(ResponseStatusException.class, () -> service.score(List.of(1, 2, 3, 4)));
        assertThrows(ResponseStatusException.class, () -> service.score(List.of(1, 2, 3, 4, 5, 6)));
        assertThrows(ResponseStatusException.class, () -> service.score(null));
    }

    @Test
    void outOfRangeResponse_throws() {
        assertThrows(ResponseStatusException.class, () -> service.score(List.of(0, 0, 0, 0, 6)));
        assertThrows(ResponseStatusException.class, () -> service.score(List.of(-1, 0, 0, 0, 0)));
    }
}
