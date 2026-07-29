package com.example.crediSense.repository;

import java.util.List;
import java.util.UUID;

import com.example.crediSense.entity.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.crediSense.entity.Dossier;

public interface DossierRepository extends JpaRepository<Dossier, UUID> {
    List<Dossier> findByClientId(UUID clientId);
    List<Dossier> findByClient(Client client);
    List<Dossier> findByStatut(String statut);
    List<Dossier> findAllByOrderByStatutAsc();

}
