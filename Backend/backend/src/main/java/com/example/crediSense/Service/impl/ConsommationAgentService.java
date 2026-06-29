package com.example.crediSense.Service.impl;

import com.example.crediSense.dto.response.CreditAnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class ConsommationAgentService {

    @Value("${groq.api.key}")
    private String groqApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public CreditAnalysisResult analyse(String documentText) {
        String systemPrompt = """
            Tu es un expert senior en crédit à la consommation bancaire tunisien.
            Analyse le dossier fourni et retourne UNIQUEMENT un JSON valide avec cette structure exacte:
            {
              "eligibility": "ELIGIBLE|REFUS|CONDITIONNEL",
              "eligibilityScore": <0-100>,
              "financialMetrics": {
                "dti": <ratio dette/revenu en %>,
                "monthlyIncome": <revenu mensuel net>,
                "requestedAmount": <montant demandé>,
                "duration": <durée en mois>,
                "monthlyPayment": <mensualité estimée>,
                "existingDebts": <total dettes existantes>
              },
              "risks": ["risque1", "risque2", ...],
              "recommendedPlan": ["étape1", "étape2", ...],
              "rawExplanation": "<explication détaillée>"
            }

            Critères consommation à évaluer:
            - DTI: acceptable si < 30%, risque si 30-35%, refus si > 35%
            - Montant max: généralement 5× le salaire mensuel net
            - Durée max: 84 mois (7 ans) selon réglementation BCT
            - Stabilité de l'emploi et ancienneté
            - Historique de remboursement (incidents de paiement)
            - Score comportemental (épargne, mouvements compte)
            - Cumul de crédits en cours
            """;

        String userMessage = "Voici le dossier à analyser:\n\n" + documentText;

        Map<String, Object> body = Map.of(
                "model", "llama-3.3-70b-versatile",
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "temperature", 0.1,
                "max_tokens", 2000
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + groqApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                "https://api.groq.com/openai/v1/chat/completions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );

        String content = extractContent(response);
        return parseResult(content, "CONSOMMATION");
    }

    // same helpers as ImmobilierAgentService
    private String extractContent(ResponseEntity<Map> res) {
        List<Map> choices = (List<Map>) res.getBody().get("choices");
        return (String) ((Map) choices.get(0).get("message")).get("content");
    }

    private CreditAnalysisResult parseResult(String raw, String type) {
        try {
            ObjectMapper mapper = new ObjectMapper();

            // Extraire le JSON entre les balises ```json ... ```
            String json = raw;
            if (raw.contains("```json")) {
                json = raw.substring(raw.indexOf("```json") + 7);
                json = json.substring(0, json.indexOf("```")).trim();
            } else if (raw.contains("```")) {
                json = raw.substring(raw.indexOf("```") + 3);
                json = json.substring(0, json.indexOf("```")).trim();
            }

            CreditAnalysisResult result = mapper.readValue(json, CreditAnalysisResult.class);
            result.setCreditType(type);
            return result;

        } catch (Exception e) {
            CreditAnalysisResult fallback = new CreditAnalysisResult();
            fallback.setCreditType(type);
            fallback.setRawExplanation(raw);
            fallback.setEligibility("INDETERMINE");
            return fallback;
        }
    }
}