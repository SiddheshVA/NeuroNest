package com.neuronest.backend.wellness;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Every query is scoped to one user: there is deliberately no "by id only" lookup used by the service, so a
 * check-in id alone never reaches another user's row.
 */
public interface WellnessCheckinRepository extends JpaRepository<WellnessCheckin, UUID> {

    Page<WellnessCheckin> findByUserId(UUID userId, Pageable pageable);

    Optional<WellnessCheckin> findFirstByUserIdOrderByCreatedAtDescIdDesc(UUID userId);

    @Modifying
    @Query("delete from WellnessCheckin w where w.id = :id and w.userId = :userId")
    int deleteOwn(@Param("id") UUID id, @Param("userId") UUID userId);

    @Modifying
    @Query("delete from WellnessCheckin w where w.userId = :userId")
    int deleteAllOwn(@Param("userId") UUID userId);
}
