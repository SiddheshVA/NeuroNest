package com.neuronest.backend.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class Who5ScoringService {

    public ScoreResult score(List<Integer> responses) {
        if (responses == null || responses.size() != 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "WHO-5 requires exactly 5 responses");
        }

        int rawScore = 0;
        for (Integer response : responses) {
            if (response == null || response < 0 || response > 5) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each WHO-5 response must be an integer between 0 and 5");
            }
            rawScore += response;
        }

        int finalScore = rawScore * 4;

        String severityBand;
        boolean riskFlag;
        if (finalScore >= 51) {
            severityBand = "good";
            riskFlag = false;
        } else if (finalScore >= 29) {
            severityBand = "mild concern";
            riskFlag = false;
        } else {
            severityBand = "moderate-to-severe";
            riskFlag = true;
        }

        return new ScoreResult(finalScore, severityBand, riskFlag);
    }
}
