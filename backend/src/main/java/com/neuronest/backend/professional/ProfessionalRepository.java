package com.neuronest.backend.professional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProfessionalRepository extends JpaRepository<Professional, UUID>, JpaSpecificationExecutor<Professional> {

    // SELECT ... FOR UPDATE: a verification decision holds the row until it commits, so a second
    // decision on the same professional waits and then sees the new status.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Professional p WHERE p.id = :id")
    Optional<Professional> findByIdForUpdate(@Param("id") UUID id);
}
