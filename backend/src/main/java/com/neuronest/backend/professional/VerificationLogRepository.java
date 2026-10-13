package com.neuronest.backend.professional;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VerificationLogRepository extends JpaRepository<VerificationLogEntry, UUID> {

    // The newest admin-side entry that carries a note (for example the reason for NEEDS_INFO).
    Optional<VerificationLogEntry> findFirstByProfessionalIdAndNoteIsNotNullAndActorNotOrderByCreatedAtDesc(
            UUID professionalId, String actor);

    List<VerificationLogEntry> findByProfessionalIdOrderByCreatedAtDesc(UUID professionalId);
}
