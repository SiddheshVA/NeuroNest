package com.neuronest.backend.professional;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VerificationLogRepository extends JpaRepository<VerificationLogEntry, UUID> {
}
