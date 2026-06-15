package com.example.crediSense.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.JsonExtraction;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JsonExtractionRepository extends JpaRepository<JsonExtraction, UUID> {
    List<JsonExtraction> findByFichierId(UUID fichierId);
    List<JsonExtraction> findByCin(String cin);
    @Query("SELECT j FROM JsonExtraction j WHERE j.fichier.dossier.id = :dossierId")
    List<JsonExtraction> findByDossierId(@Param("dossierId") UUID dossierId);

}
