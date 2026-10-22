package com.neuronest.backend.professional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    // ---- For the one-time rename of older documents ----

    @Query("SELECT DISTINCT d.professionalId FROM ProfessionalDocument d")
    List<UUID> findProfessionalIdsWithDocuments();

    /** Changes one document's path, only if it still has the path the file was moved from; 1 when changed. */
    @Modifying
    @Query("UPDATE ProfessionalDocument d SET d.storagePath = :newPath WHERE d.id = :id AND d.storagePath = :oldPath")
    int updateStoragePath(@Param("id") UUID id, @Param("oldPath") String oldPath, @Param("newPath") String newPath);
}
