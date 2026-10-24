package com.neuronest.backend.professional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Read-only access for the public directory. Deliberately a plain Repository (no findById/findAll):
// every query here includes VISIBLE, so a hidden listing cannot be fetched through this interface.
public interface PublicProfessionalRepository extends Repository<Professional, UUID> {

    // The one definition of "publicly visible". :today is passed in by the service. A row that has a
    // professional account is a real person, so it never counts as sample data.
    String VISIBLE = """
            p.active = true
            AND ((p.verification_status = 'VERIFIED'
                  AND (p.registration_expiry IS NULL OR p.registration_expiry >= :today))
                 OR (p.is_sample_data = true
                     AND NOT EXISTS (SELECT 1 FROM professional_accounts pa WHERE pa.professional_id = p.id)))
            """;

    // Null parameters mean "no filter". Casts give Postgres a type for null binds.
    String FILTERS = """
            AND (CAST(:credentialType AS text) IS NULL OR p.credential_type = CAST(:credentialType AS text))
            AND (CAST(:city AS text) IS NULL OR lower(trim(p.city)) = lower(CAST(:city AS text)))
            AND (CAST(:mode AS text) IS NULL OR p.consultation_mode = CAST(:mode AS text) OR p.consultation_mode = 'BOTH')
            AND (CAST(:language AS text) IS NULL OR EXISTS (
                    SELECT 1 FROM jsonb_array_elements_text(p.languages) AS lang
                    WHERE lower(trim(lang)) = lower(CAST(:language AS text))))
            AND (CAST(:pattern AS text) IS NULL
                 OR lower(p.full_name) LIKE CAST(:pattern AS text) ESCAPE '\\'
                 OR EXISTS (
                    SELECT 1 FROM jsonb_array_elements_text(p.specialties) AS spec
                    WHERE lower(spec) LIKE CAST(:pattern AS text) ESCAPE '\\'))
            """;

    // C in the smoothed rating: the average of every visible review, or 4.0 while there are none.
    String PRIOR_RATING = "(SELECT COALESCE(avg(r.rating), 4.0) FROM professional_reviews r WHERE r.status = 'VISIBLE')";

    // (v / (v + 5)) * R + (5 / (v + 5)) * C: few reviews lean towards C, so one 5-star review cannot beat
    // fifty that average 4.8.
    String SMOOTHED_RATING = "((p.rating_count::numeric / (p.rating_count + 5)) * COALESCE(p.rating_avg, 0)"
            + " + (5.0 / (p.rating_count + 5)) * " + PRIOR_RATING + ")";

    // Real (verified) listings first, then sample listings, always. :sort then adds its keys (each CASE is
    // null, so neutral, for the other sorts); a null :sort keeps the default, by name. Name and id last
    // keep paging stable.
    String ORDER = " ORDER BY p.is_sample_data ASC,"
            + " CASE WHEN CAST(:sort AS text) = 'TOP_RATED' THEN p.rating_count = 0 END ASC,"
            + " CASE WHEN CAST(:sort AS text) = 'TOP_RATED' THEN " + SMOOTHED_RATING + " END DESC,"
            + " CASE WHEN CAST(:sort AS text) IN ('TOP_RATED', 'MOST_REVIEWED') THEN p.rating_count END DESC,"
            + " CASE WHEN CAST(:sort AS text) = 'MOST_REVIEWED' THEN " + SMOOTHED_RATING + " END DESC,"
            + " CASE WHEN CAST(:sort AS text) = 'LOWEST_FEE' THEN p.consultation_fee END ASC NULLS LAST,"
            + " CASE WHEN CAST(:sort AS text) = 'MOST_EXPERIENCED' THEN p.years_experience END DESC NULLS LAST,"
            + " lower(p.full_name) ASC, p.id ASC";

    @Query(value = "SELECT p.* FROM professionals p WHERE " + VISIBLE + FILTERS + ORDER,
            countQuery = "SELECT count(*) FROM professionals p WHERE " + VISIBLE + FILTERS,
            nativeQuery = true)
    Page<Professional> search(@Param("today") LocalDate today,
                              @Param("credentialType") String credentialType,
                              @Param("city") String city,
                              @Param("mode") String mode,
                              @Param("language") String language,
                              @Param("pattern") String pattern,
                              @Param("sort") String sort,
                              Pageable pageable);

    @Query(value = "SELECT p.* FROM professionals p WHERE p.id = :id AND " + VISIBLE, nativeQuery = true)
    Optional<Professional> findVisibleById(@Param("id") UUID id, @Param("today") LocalDate today);

    // Candidates for "similar professionals": visible, real (not sample), the same credential type and not
    // the excluded one; the same city first, then the most recently verified. The caller keeps only the
    // bookable ones, so a few more than needed are returned.
    @Query(value = "SELECT p.* FROM professionals p WHERE " + VISIBLE
            + " AND p.is_sample_data = false AND p.credential_type = :credentialType AND p.id <> :excludeId"
            + " ORDER BY CASE WHEN CAST(:city AS text) IS NOT NULL"
            + "   AND lower(trim(p.city)) = lower(trim(CAST(:city AS text))) THEN 0 ELSE 1 END,"
            + " p.verified_at DESC NULLS LAST, p.id ASC LIMIT 20",
            nativeQuery = true)
    List<Professional> findSimilarCandidates(@Param("today") LocalDate today,
                                             @Param("credentialType") String credentialType,
                                             @Param("city") String city,
                                             @Param("excludeId") UUID excludeId);

    // One entry per city regardless of case or surrounding spaces.
    @Query(value = "SELECT min(trim(p.city)) FROM professionals p WHERE " + VISIBLE
            + " AND p.city IS NOT NULL AND trim(p.city) <> '' GROUP BY lower(trim(p.city)) ORDER BY lower(trim(p.city))",
            nativeQuery = true)
    List<String> findVisibleCities(@Param("today") LocalDate today);

    @Query(value = "SELECT min(trim(lang)) FROM professionals p, jsonb_array_elements_text(p.languages) AS lang WHERE "
            + VISIBLE + " AND trim(lang) <> '' GROUP BY lower(trim(lang)) ORDER BY lower(trim(lang))",
            nativeQuery = true)
    List<String> findVisibleLanguages(@Param("today") LocalDate today);
}
