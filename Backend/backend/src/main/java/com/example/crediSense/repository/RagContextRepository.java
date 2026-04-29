package com.example.crediSense.repository;

import com.example.crediSense.entity.RagContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RagContextRepository extends JpaRepository<RagContext, UUID> {

    // 🔍 Tous les contextes d’un client (clé principale pour ton RAG)
    List<RagContext> findByCin(String cin);

    // 🔍 Contextes liés à un dossier
    List<RagContext> findByDossierId(UUID dossierId);

    // 🔍 Filtrer par source (OCR, PDF, JSON…)
    List<RagContext> findBySource(String source);

    // 🔍 Combinaison utile pour filtrer précisément
    List<RagContext> findByCinAndSource(String cin, String source);

    // 🔍 Vérifier existence
    boolean existsByCin(String cin);

    void deleteByCin(String cin);
}