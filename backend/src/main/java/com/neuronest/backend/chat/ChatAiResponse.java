package com.neuronest.backend.chat;

import com.neuronest.backend.safety.CrisisResource;

import java.util.List;

public record ChatAiResponse(
        String reply,
        boolean riskFlag,
        String riskReason,
        int recommendationTier,
        String recommendedAction,
        String recommendedInstrument,
        List<CrisisResource> crisisResources
) {}