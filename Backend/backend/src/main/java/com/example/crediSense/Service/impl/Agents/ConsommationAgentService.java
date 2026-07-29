package com.example.crediSense.Service.impl.Agents;

import com.example.crediSense.dto.response.CreditAnalysisResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsommationAgentService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${python.ai.url:http://localhost:8002}")
    private String pythonAiUrl;

    /**
     * Analyse un dossier crédit consommation.
     * Appelle le service Python /ai/score/consommation
     * au lieu d'appeler GROQ directement.
     */
    public CreditAnalysisResult analyse(String documentText) {
        log.info("Appel agent consommation Python — {} chars", documentText.length());

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = Map.of("document_text", documentText);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    pythonAiUrl + "/ai/score/consommation",
                    new HttpEntity<>(body, headers),
                    String.class
            );

            JsonNode node = objectMapper.readTree(response.getBody());

            // Mapper vers CreditAnalysisResult
            CreditAnalysisResult result = new CreditAnalysisResult();
            result.setCreditType("CONSOMMATION");
            result.setEligibility(node.path("eligibility").asText("INDETERMINE"));
            result.setEligibilityScore(
                    node.path("eligibilityScore").isNull() ? null :
                            node.path("eligibilityScore").asInt()
            );
            result.setRawExplanation(node.path("rawExplanation").asText());

            // Risks et recommendedPlan
            if (node.has("risks")) {
                result.setRisks(
                        objectMapper.convertValue(node.get("risks"),
                                objectMapper.getTypeFactory().constructCollectionType(
                                        java.util.List.class, String.class))
                );
            }
            if (node.has("recommendedPlan")) {
                result.setRecommendedPlan(
                        objectMapper.convertValue(node.get("recommendedPlan"),
                                objectMapper.getTypeFactory().constructCollectionType(
                                        java.util.List.class, String.class))
                );
            }

            // Score final pour sauvegarde
            result.setScoreFinal(
                    node.path("eligibilityScore").isNull() ? 0.0 :
                            node.path("eligibilityScore").asDouble()
            );
            result.setDecisionFinale(result.getEligibility());
            result.setJustificationGlobale(result.getRawExplanation());

            log.info("Agent consommation — eligibility={}, score={}",
                    result.getEligibility(), result.getEligibilityScore());

            return result;

        } catch (Exception e) {
            log.error("Erreur appel agent Python : {}", e.getMessage());
            CreditAnalysisResult fallback = new CreditAnalysisResult();
            fallback.setCreditType("CONSOMMATION");
            fallback.setEligibility("INDETERMINE");
            fallback.setRawExplanation("Erreur : " + e.getMessage());
            return fallback;
        }
    }
}