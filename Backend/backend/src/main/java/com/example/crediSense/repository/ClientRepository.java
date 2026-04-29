package com.example.crediSense.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.Client;
public interface ClientRepository extends JpaRepository<Client, UUID> {
    Optional<Client> findByCin(String cin);
}