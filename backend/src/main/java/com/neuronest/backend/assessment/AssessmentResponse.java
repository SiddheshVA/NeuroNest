package com.neuronest.backend.assessment;

import com.neuronest.backend.safety.CrisisResource;

import java.util.List;
import java.util.UUID;

public record AssessmentResponse(
        UUID id,
        int score,
        String severityBand,
        boolean riskFlag,
        List<CrisisResource> crisisResources
) {}
