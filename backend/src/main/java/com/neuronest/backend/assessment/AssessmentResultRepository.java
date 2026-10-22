package com.neuronest.backend.assessment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AssessmentResultRepository extends JpaRepository<AssessmentResult, UUID> {
    List<AssessmentResult> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);
    List<AssessmentResult> findBySessionIdInOrderByCreatedAtDesc(List<UUID> sessionIds);

    /**
     * A signed-in user's own results, newest first: only results saved under sessions whose user_id is this
     * user (set from the verified token when the session was created). Anonymous sessions never count.
     */
    @Query("""
            select a from AssessmentResult a
            where a.sessionId in (select s.id from Session s where s.userId = :userId)
            order by a.createdAt desc, a.id desc""")
    List<AssessmentResult> findLatestForUser(@Param("userId") UUID userId, Pageable pageable);
}
