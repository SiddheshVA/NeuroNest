package com.neuronest.backend.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class Phq9ScoringService {

    public ScoreResult score(List<Integer> responses) {
        if (responses == null || responses.size() != 9) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "PHQ-9 requires exactly 9 responses");
        }

        int rawScore = 0;
        for (Integer response : responses) {
            if (response == null || response < 0 || response > 3) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each PHQ-9 response must be an integer between 0 and 3");
            }
            rawScore += response;
        }

        String severityBand;
        if (rawScore <= 4) severityBand = "minimal";
        else if (rawScore <= 9) severityBand = "mild";
        else if (rawScore <= 14) severityBand = "moderate";
        else if (rawScore <= 19) severityBand = "moderately severe";
        else severityBand = "severe";

        // Standard PHQ-9 safety rule: any endorsement of item 9 (suicidal
        // ideation) flags risk regardless of the total-score band.
        boolean suicidalIdeation = responses.get(8) >= 1;
        boolean riskFlag = rawScore >= 20 || suicidalIdeation;

        return new ScoreResult(rawScore, severityBand, riskFlag);
    }
}
