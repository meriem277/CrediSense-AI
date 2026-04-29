package com.example.crediSense.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.AgentAnalysis;

public interface AgentAnalysisRepository extends JpaRepository<AgentAnalysis, UUID> {
    Optional<AgentAnalysis> findByDossierId(UUID dossierId);
}