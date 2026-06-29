package com.example.crediSense.agent;

import lombok.Data;
import java.util.List;

@Data
public class Agent1Result {
    private String cleanText;
    private String detectedLanguage; // fr | ar | mixed
    private String documentType; // fiche_paie|releve_bancaire|contrat_travail|attestation_salaire|compromis_vente|declaration_fiscale|unknown
    private List<String> detectedSections;
}
