package com.neuronest.backend.assessment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssessmentResultRepository extends JpaRepository<AssessmentResult, UUID> {
    List<AssessmentResult> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);
    List<AssessmentResult> findBySessionIdInOrderByCreatedAtDesc(List<UUID> sessionIds);
}
