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
public class ImmobilierAgentService {

    @Value("${groq.api.key}")
    private String groqApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public CreditAnalysisResult analyse(String documentText) {
        String systemPrompt = """
            Tu es un expert senior en crédit immobilier bancaire tunisien.
            Analyse le dossier fourni et retourne UNIQUEMENT un JSON valide avec cette structure exacte:
            {
              "eligibility": "ELIGIBLE|REFUS|CONDITIONNEL",
              "eligibilityScore": <0-100>,
              "financialMetrics": {
                "dti": <ratio dette/revenu en %>,
                "ltv": <ratio prêt/valeur bien en %>,
                "monthlyIncome": <revenu mensuel net>,
                "requestedAmount": <montant demandé>,
                "duration": <durée en mois>,
                "monthlyPayment": <mensualité estimée>
              },
              "risks": ["risque1", "risque2", ...],
              "recommendedPlan": ["étape1", "étape2", ...],
              "rawExplanation": "<explication détaillée>"
            }

            Critères immobilier à évaluer:
            - DTI (Debt-to-Income): acceptable si < 33%, risque si 33-40%, refus si > 40%
            - LTV (Loan-to-Value): acceptable si < 80%, risque si 80-90%, refus si > 90%
            - Stabilité professionnelle (CDI vs CDD vs indépendant)
            - Garanties (hypothèque, caution)
            - Historique bancaire
            - Apport personnel minimum 10%
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
        return parseResult(content, "IMMOBILIER");
    }

    private String extractContent(ResponseEntity<Map> response) {
        List<Map> choices = (List<Map>) response.getBody().get("choices");
        Map message = (Map) choices.get(0).get("message");
        return (String) message.get("content");
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
