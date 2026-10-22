package com.neuronest.backend.professional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfessionalDayOffRepository extends JpaRepository<ProfessionalDayOff, UUID> {

    /** Entries that end on or after {@code today}, soonest first. */
    List<ProfessionalDayOff> findByProfessionalIdAndDateToGreaterThanEqualOrderByDateFromAscIdAsc(UUID professionalId,
                                                                                                   LocalDate today);

    long countByProfessionalIdAndDateToGreaterThanEqual(UUID professionalId, LocalDate today);

    /** Entries that share at least one date with [from, to]. */
    @Query("""
            select d from ProfessionalDayOff d
            where d.professionalId = :professionalId and d.dateFrom <= :to and d.dateTo >= :from
            order by d.dateFrom, d.id""")
    List<ProfessionalDayOff> findOverlapping(@Param("professionalId") UUID professionalId,
                                             @Param("from") LocalDate from, @Param("to") LocalDate to);

    Optional<ProfessionalDayOff> findByIdAndProfessionalId(UUID id, UUID professionalId);
}
