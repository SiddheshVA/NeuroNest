package com.neuronest.backend.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class Pss10ScoringService {

    // 0-indexed items 4, 5, 7, 8 (commonly numbered 1-10) are reverse-scored.
    private static final Set<Integer> REVERSE_SCORED_INDICES = Set.of(3, 4, 6, 7);

    public ScoreResult score(List<Integer> responses) {
        if (responses == null || responses.size() != 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PSS-10 requires exactly 10 responses");
        }

        int totalScore = 0;
        for (int i = 0; i < responses.size(); i++) {
            Integer response = responses.get(i);
            if (response == null || response < 0 || response > 4) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each PSS-10 response must be an integer between 0 and 4");
            }
            totalScore += REVERSE_SCORED_INDICES.contains(i) ? (4 - response) : response;
        }

        String severityBand;
        if (totalScore <= 13) severityBand = "low stress";
        else if (totalScore <= 26) severityBand = "moderate stress";
        else severityBand = "high stress";

        boolean riskFlag = totalScore >= 27;

        return new ScoreResult(totalScore, severityBand, riskFlag);
    }
}
