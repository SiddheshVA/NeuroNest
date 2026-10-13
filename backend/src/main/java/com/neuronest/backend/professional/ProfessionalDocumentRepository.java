package com.neuronest.backend.professional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfessionalDocumentRepository extends JpaRepository<ProfessionalDocument, UUID> {

    List<ProfessionalDocument> findByProfessionalIdOrderByUploadedAtAsc(UUID professionalId);

    // Scoped by owner, so one professional can never reach another's document.
    Optional<ProfessionalDocument> findByIdAndProfessionalId(UUID id, UUID professionalId);

    long countByProfessionalId(UUID professionalId);

    // Document counts for several professionals in one query: rows of [professionalId, count].
    @Query("SELECT d.professionalId, count(d) FROM ProfessionalDocument d WHERE d.professionalId IN :ids GROUP BY d.professionalId")
    List<Object[]> countByProfessionalIds(@Param("ids") Collection<UUID> ids);
}
