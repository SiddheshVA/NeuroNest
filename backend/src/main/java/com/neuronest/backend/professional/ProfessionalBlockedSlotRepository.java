package com.neuronest.backend.professional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfessionalBlockedSlotRepository extends JpaRepository<ProfessionalBlockedSlot, UUID> {

    /** This professional's blocks that reach into [from, to), earliest first. */
    @Query("""
            select s from ProfessionalBlockedSlot s
            where s.professionalId = :professionalId and s.startsAt < :to and s.blockedUntil > :from
            order by s.startsAt, s.id""")
    List<ProfessionalBlockedSlot> findOverlapping(@Param("professionalId") UUID professionalId,
                                                  @Param("from") Instant from, @Param("to") Instant to);

    Optional<ProfessionalBlockedSlot> findByIdAndProfessionalId(UUID id, UUID professionalId);
}
