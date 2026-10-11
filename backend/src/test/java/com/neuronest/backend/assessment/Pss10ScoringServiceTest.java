package com.neuronest.backend.assessment;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pss10ScoringServiceTest {

    private final Pss10ScoringService service = new Pss10ScoringService();

    // Reverse-scored items in the code (0-indexed): 3, 4, 6, 7
    // i.e. items 4, 5, 7, 8 in the commonly-used 1-indexed PSS-10 numbering.
    private static final List<Integer> REVERSE_INDICES = List.of(3, 4, 6, 7);

    private static List<Integer> allSetTo(int value) {
        Integer[] arr = new Integer[10];
        java.util.Arrays.fill(arr, value);
        return List.of(arr);
    }

    @Test
    void reverseScoredItemAnsweredFour_contributesZero_notFour() {
        // Set only the reverse-scored items to 4, everything else to 0.
        // If reversal is applied, each contributes (4-4)=0, so the total must
        // be 0 - not 16, which is what it would be without reversal.
        Integer[] arr = new Integer[10];
        java.util.Arrays.fill(arr, 0);
        for (int i : REVERSE_INDICES) arr[i] = 4;

        ScoreResult result = service.score(List.of(arr));
        assertEquals(0, result.score(), "reverse-scored items answered 4 should contribute 0 points each");
        assertEquals("low stress", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void reverseScoredItemAnsweredZero_contributesFour_notZero() {
        // Set only the reverse-scored items to 0, everything else to 0.
        // Reversal turns a raw 0 into a contribution of (4-0)=4 per item,
        // so 4 reverse items should add 16 points, not 0.
        Integer[] arr = new Integer[10];
        java.util.Arrays.fill(arr, 0);
        // reverse indices already 0, no change needed - contribution is (4-0)=4 each
        ScoreResult result = service.score(List.of(arr));
        assertEquals(16, result.score(), "4 reverse-scored items answered 0 should contribute 4 points each (16 total)");
        assertEquals("moderate stress", result.severityBand());
    }

    @Test
    void trueMinimum_isNotAllZero_dueToReversal() {
        // Because 4 of the 10 items are reverse-scored, the actual lowest
        // achievable total requires the reverse items at 4 (contributing 0)
        // and the rest at 0 - not the naive "all responses = 0" case, which
        // actually yields 16 (see reverseScoredItemAnsweredZero... above).
        Integer[] arr = new Integer[10];
        java.util.Arrays.fill(arr, 0);
        for (int i : REVERSE_INDICES) arr[i] = 4;

        ScoreResult result = service.score(List.of(arr));
        assertEquals(0, result.score());
        assertEquals("low stress", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void trueMaximum_isNotAllFour_dueToReversal() {
        // The actual highest achievable total requires the reverse items at 0
        // (contributing 4 each) and the rest at 4 - not "all responses = 4",
        // which only yields 24 (6 non-reverse items x 4, reverse items x 0).
        Integer[] arr = new Integer[10];
        java.util.Arrays.fill(arr, 4);
        for (int i : REVERSE_INDICES) arr[i] = 0;

        ScoreResult result = service.score(List.of(arr));
        assertEquals(40, result.score());
        assertEquals("high stress", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void allFourResponses_isOnlyModerate_notMaximum() {
        ScoreResult result = service.score(allSetTo(4));
        assertEquals(24, result.score());
        assertEquals("moderate stress", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_lowStress_upperEdge() {
        // Non-reverse indices {0,1,2,5,8,9} sum to 13; reverse indices held at
        // 4 (contributing 0 each) so they don't add to the total.
        List<Integer> responses = List.of(4, 4, 4, 4, 4, 1, 4, 4, 0, 0);
        ScoreResult result = service.score(responses);
        assertEquals(13, result.score());
        assertEquals("low stress", result.severityBand());
    }

    @Test
    void boundary_moderateStress_lowerEdge() {
        List<Integer> responses = List.of(4, 4, 4, 4, 4, 2, 4, 4, 0, 0);
        ScoreResult result = service.score(responses);
        assertEquals(14, result.score());
        assertEquals("moderate stress", result.severityBand());
    }

    @Test
    void boundary_moderateStress_upperEdge_noRiskYet() {
        // Non-reverse items all at 4 (sum 24) plus reverse item 4 (index 4)
        // set to raw 2, contributing (4-2)=2, for a total of 26.
        List<Integer> responses = List.of(4, 4, 4, 4, 2, 4, 4, 4, 4, 4);
        ScoreResult result = service.score(responses);
        assertEquals(26, result.score());
        assertEquals("moderate stress", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_highStress_lowerEdge_flagsRisk() {
        // Same as above but reverse item 4 (index 4) set to raw 1, contributing 3.
        List<Integer> responses = List.of(4, 4, 4, 4, 1, 4, 4, 4, 4, 4);
        ScoreResult result = service.score(responses);
        assertEquals(27, result.score());
        assertEquals("high stress", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void wrongNumberOfResponses_throws() {
        assertThrows(ResponseStatusException.class, () -> service.score(allSetTo(0).subList(0, 9)));
        List<Integer> tooMany = new ArrayList<>(allSetTo(0));
        tooMany.add(0);
        assertThrows(ResponseStatusException.class, () -> service.score(tooMany));
        assertThrows(ResponseStatusException.class, () -> service.score(null));
    }

    @Test
    void outOfRangeResponse_throws() {
        List<Integer> tooHigh = new ArrayList<>(allSetTo(0));
        tooHigh.set(0, 5);
        assertThrows(ResponseStatusException.class, () -> service.score(tooHigh));

        List<Integer> negative = new ArrayList<>(allSetTo(0));
        negative.set(0, -1);
        assertThrows(ResponseStatusException.class, () -> service.score(negative));
    }
}
