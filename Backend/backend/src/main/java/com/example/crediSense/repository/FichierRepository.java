package com.example.crediSense.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.Fichier;

public interface FichierRepository extends JpaRepository<Fichier, UUID> {
    List<Fichier> findByCin(String cin);
    List<Fichier> findByAgentId(UUID agentId);
}