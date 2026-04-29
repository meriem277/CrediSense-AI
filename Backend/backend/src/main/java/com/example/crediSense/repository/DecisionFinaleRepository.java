package com.example.crediSense.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.DecisionFinale;

public interface DecisionFinaleRepository extends JpaRepository<DecisionFinale, UUID> {
    Optional<DecisionFinale> findByDossierId(UUID dossierId);
}
