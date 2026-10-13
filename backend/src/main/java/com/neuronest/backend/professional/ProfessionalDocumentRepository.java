package com.neuronest.backend.professional;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfessionalDocumentRepository extends JpaRepository<ProfessionalDocument, UUID> {

    List<ProfessionalDocument> findByProfessionalIdOrderByUploadedAtAsc(UUID professionalId);

    // Scoped by owner, so one professional can never reach another's document.
    Optional<ProfessionalDocument> findByIdAndProfessionalId(UUID id, UUID professionalId);

    long countByProfessionalId(UUID professionalId);
}
