package com.example.crediSense.repository;

import com.example.crediSense.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /** Le journal d'un dossier, du plus ancien au plus récent. */
    List<AuditEvent> findByDossierIdOrderByCreatedAtAsc(UUID dossierId);
}
