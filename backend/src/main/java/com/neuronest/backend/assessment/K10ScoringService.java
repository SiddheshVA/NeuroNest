package com.neuronest.backend.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class K10ScoringService {

    public ScoreResult score(List<Integer> responses) {
        if (responses == null || responses.size() != 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "K10 requires exactly 10 responses");
        }

        int rawScore = 0;
        for (Integer response : responses) {
            if (response == null || response < 1 || response > 5) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Each K10 response must be an integer between 1 and 5");
            }
            rawScore += response;
        }

        String severityBand;
        if (rawScore < 20) severityBand = "likely well";
        else if (rawScore <= 24) severityBand = "mild distress";
        else if (rawScore <= 29) severityBand = "moderate distress";
        else severityBand = "severe distress";

        boolean riskFlag = rawScore >= 30;

        return new ScoreResult(rawScore, severityBand, riskFlag);
    }
}
