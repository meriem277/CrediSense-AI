package com.example.crediSense.dto.request;

public record DemandeRequest(
        String  cin,
        String  nom,
        String  prenom,
        String  clientEmail,
        String  telephone,
        String  adresse,
        String  typeContrat,
        String  nationalite,
        String  dateNaissance,
        Double  montantCredit,
        Integer dureeCredit
) {}