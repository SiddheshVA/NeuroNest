package com.neuronest.backend.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class Gad7ScoringService {

    public ScoreResult score(List<Integer> responses) {
        if (responses == null || responses.size() != 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "GAD-7 requires exactly 7 responses");
        }

        int rawScore = 0;
        for (Integer response : responses) {
            if (response == null || response < 0 || response > 3) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each GAD-7 response must be an integer between 0 and 3");
            }
            rawScore += response;
        }

        String severityBand;
        if (rawScore <= 4) severityBand = "minimal";
        else if (rawScore <= 9) severityBand = "mild";
        else if (rawScore <= 14) severityBand = "moderate";
        else severityBand = "severe";

        boolean riskFlag = rawScore >= 15;

        return new ScoreResult(rawScore, severityBand, riskFlag);
    }
}
