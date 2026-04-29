package com.example.crediSense.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.OcrResult;

public interface OcrResultRepository extends JpaRepository<OcrResult, UUID> {
    Optional<OcrResult> findByFichierId(UUID fichierId);
}