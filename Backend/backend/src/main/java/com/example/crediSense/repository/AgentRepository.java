package com.example.crediSense.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.Agent;
import com.example.crediSense.entity.RoleType;

public interface AgentRepository extends JpaRepository<Agent, UUID> {
    List<Agent> findByRole(RoleType role);
   Optional<Agent> findByEmail(String email);
    boolean existsByEmail(String email);
}
