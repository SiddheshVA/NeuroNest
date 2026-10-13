package com.neuronest.backend.professional;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProfessionalAccountRepository extends JpaRepository<ProfessionalAccount, UUID> {

    Optional<ProfessionalAccount> findByClerkUserId(String clerkUserId);

    boolean existsByEmailIgnoreCase(String email);
}
