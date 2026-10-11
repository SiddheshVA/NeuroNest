package com.neuronest.backend.assessment;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K10ScoringServiceTest {

    private final K10ScoringService service = new K10ScoringService();

    // K10's valid response range is 1-5 (not 0-5 like the other instruments),
    // so the true minimum is all-1's (rawScore 10), not all-zero - an
    // all-zero list is actually invalid input for this instrument.
    private static List<Integer> responses(int rawSum) {
        int extra = rawSum - 10;
        Integer[] arr = new Integer[10];
        for (int i = 0; i < 10; i++) {
            int add = Math.min(4, extra);
            arr[i] = 1 + add;
            extra -= add;
        }
        return List.of(arr);
    }

    @Test
    void allOnesResponses_isTrueMinimum_likelyWell_noRisk() {
        ScoreResult result = service.score(responses(10));
        assertEquals(10, result.score());
        assertEquals("likely well", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void allZeroResponses_isInvalid_forK10() {
        // Unlike the other instruments, 0 is out of K10's valid 1-5 range.
        assertThrows(ResponseStatusException.class,
                () -> service.score(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void allMaxResponses_isSevereDistress_withRisk() {
        ScoreResult result = service.score(responses(50));
        assertEquals(50, result.score());
        assertEquals("severe distress", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void boundary_likelyWell_upperEdge() {
        ScoreResult result = service.score(responses(19));
        assertEquals(19, result.score());
        assertEquals("likely well", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_mildDistress_lowerEdge() {
        ScoreResult result = service.score(responses(20));
        assertEquals(20, result.score());
        assertEquals("mild distress", result.severityBand());
    }

    @Test
    void boundary_mildDistress_upperEdge() {
        ScoreResult result = service.score(responses(24));
        assertEquals(24, result.score());
        assertEquals("mild distress", result.severityBand());
    }

    @Test
    void boundary_moderateDistress_lowerEdge() {
        ScoreResult result = service.score(responses(25));
        assertEquals(25, result.score());
        assertEquals("moderate distress", result.severityBand());
    }

    @Test
    void boundary_moderateDistress_upperEdge_noRiskYet() {
        ScoreResult result = service.score(responses(29));
        assertEquals(29, result.score());
        assertEquals("moderate distress", result.severityBand());
        assertFalse(result.riskFlag());
    }

    @Test
    void boundary_severeDistress_lowerEdge_flagsRisk() {
        ScoreResult result = service.score(responses(30));
        assertEquals(30, result.score());
        assertEquals("severe distress", result.severityBand());
        assertTrue(result.riskFlag());
    }

    @Test
    void wrongNumberOfResponses_throws() {
        assertThrows(ResponseStatusException.class, () -> service.score(responses(15).subList(0, 9)));
        List<Integer> tooMany = new ArrayList<>(responses(15));
        tooMany.add(1);
        assertThrows(ResponseStatusException.class, () -> service.score(tooMany));
        assertThrows(ResponseStatusException.class, () -> service.score(null));
    }

    @Test
    void outOfRangeResponse_throws() {
        List<Integer> tooHigh = new ArrayList<>(responses(10));
        tooHigh.set(0, 6);
        assertThrows(ResponseStatusException.class, () -> service.score(tooHigh));

        List<Integer> tooLow = new ArrayList<>(responses(10));
        tooLow.set(0, 0);
        assertThrows(ResponseStatusException.class, () -> service.score(tooLow));
    }
}
