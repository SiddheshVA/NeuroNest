package com.neuronest.backend.wellness;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way for other modules (later: Companion, Dashboard, Relaxation) to learn about a user's check-ins:
 * a compact summary of that user's own latest one. It never exposes answers, probabilities or drivers, and it
 * is for the user's own experience only; never for professionals, admins or assessment sharing.
 */
@Service
public class WellnessContextProvider {

    /**
     * @param moodBand     Low, Moderate or High
     * @param checkedInAt  when the check-in was made
     * @param sleepConcern the sleep result was an insomnia-like or apnea-like pattern
     * @param sleepClass   none, insomnia_like, apnea_like, or null when sleep was outside the model's range
     */
    public record WellnessSummary(String moodBand, Instant checkedInAt, boolean sleepConcern, String sleepClass) {}

    private final WellnessCheckinRepository checkins;

    public WellnessContextProvider(WellnessCheckinRepository checkins) {
        this.checkins = checkins;
    }

    /** The user's latest check-in, summarised; empty if they have none (or userId is null). */
    @Transactional(readOnly = true)
    public Optional<WellnessSummary> latestSummary(UUID userId) {
        if (userId == null) return Optional.empty();
        return checkins.findFirstByUserIdOrderByCreatedAtDescIdDesc(userId).map(row -> new WellnessSummary(
                row.getMoodBand(), row.getCreatedAt(),
                "insomnia_like".equals(row.getSleepClass()) || "apnea_like".equals(row.getSleepClass()),
                row.getSleepClass()));
    }
}
