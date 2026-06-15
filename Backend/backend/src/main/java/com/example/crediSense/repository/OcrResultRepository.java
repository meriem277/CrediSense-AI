package com.example.crediSense.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.OcrResult;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OcrResultRepository extends JpaRepository<OcrResult, UUID> {
    Optional<OcrResult> findByFichierId(UUID fichierId);
    // ← Filtrer par dossier (via Fichier → Dossier)
    @Query("SELECT o FROM OcrResult o WHERE o.fichier.dossier.id = :dossierId")
    List<OcrResult> findByDossierId(@Param("dossierId") UUID dossierId);
}