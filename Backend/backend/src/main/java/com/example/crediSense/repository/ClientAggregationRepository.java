package com.example.crediSense.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.ClientAggregation;


public interface ClientAggregationRepository extends JpaRepository<ClientAggregation, UUID> {
    Optional<ClientAggregation> findByCin(String cin);
}