package com.example.crediSense.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.JsonExtraction;

public interface JsonExtractionRepository extends JpaRepository<JsonExtraction, UUID> {
    List<JsonExtraction> findByFichierId(UUID fichierId);
    List<JsonExtraction> findByCin(String cin);
}
