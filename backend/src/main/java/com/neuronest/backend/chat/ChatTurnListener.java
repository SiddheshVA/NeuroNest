package com.neuronest.backend.chat;

import com.neuronest.backend.safety.CrisisResource;

import java.util.List;
import java.util.UUID;

/**
 * Receives the events of one chat turn, in order: meta, then tokens, then done —
 * or error at any point (after which nothing else is sent).
 */
public interface ChatTurnListener {

    /** Step A result. Always the first event of a successful turn, before any reply text. */
    void onMeta(SafetyAssessment assessment);

    /** One chunk of reply text. */
    void onToken(String text);

    /** The full reply was streamed and saved. */
    void onDone(UUID assistantMessageId);

    /** The turn failed. crisisResources is non-null when the user should still see the helplines. */
    void onError(ChatTurnError error, List<CrisisResource> crisisResources);
}
