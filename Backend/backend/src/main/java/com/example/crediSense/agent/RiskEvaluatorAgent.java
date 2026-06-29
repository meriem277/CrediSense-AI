package com.example.crediSense.agent;

import com.example.crediSense.Service.GroqClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RiskEvaluatorAgent {

    private final GroqClient groqClient;
    private final ToolExecutor toolExecutor; // kept for future use

    public Agent3Result run(Agent2Result financials, String creditType, String cin) {
        // null-safe check: if financials lacks any usable numeric data, return an indeterminate result
        boolean hasFinancialData = financials != null && (
                financials.getMonthlyIncome() != null ||
                financials.getExistingDebts() != null ||
                financials.getRequestedAmount() != null ||
                financials.getPropertyValue() != null ||
                financials.getDti() != null ||
                financials.getLtv() != null
        );

        if (!hasFinancialData) {
            System.out.println("=== AGENT 3 WARNING: financials missing or empty, returning default INDETERMINE ===");
            Agent3Result indet = new Agent3Result();
            indet.setEligibility("INDETERMINE");
            indet.setEligibilityScore(0);
            indet.setRisks(java.util.List.of("HIGH: Données financières insuffisantes (N/A)"));
            indet.setDecisionRationale("Impossible d'évaluer: données manquantes dans le document");
            return indet;
        }

        // Build a null-safe, formatted financials string
        String financialsStr = String.format(
                "monthlyIncome=%s, existingDebts=%s, requestedAmount=%s, propertyValue=%s, durationMonths=%s, dti=%s, ltv=%s, personalContribution=%s, employmentType=%s, employmentYears=%s, hasCreditIncidents=%s, creditPurpose=%s",
                nullSafe(financials.getMonthlyIncome()),
                nullSafe(financials.getExistingDebts()),
                nullSafe(financials.getRequestedAmount()),
                nullSafe(financials.getPropertyValue()),
                nullSafe(financials.getDurationMonths()),
                nullSafe(financials.getDti()),
                nullSafe(financials.getLtv()),
                nullSafe(financials.getPersonalContribution()),
                nullSafe(financials.getEmploymentType()),
                nullSafe(financials.getEmploymentYears()),
                nullSafe(financials.getHasCreditIncidents()),
                nullSafe(financials.getCreditPurpose())
        );

        String systemPrompt = "Tu es un évaluateur de risques crédit expérimenté. Reçois des données financières formatées, applique la grille BCT et retourne uniquement un JSON brut (sans markdown ni backticks). DTI est fourni en décimal (par ex. 0.28 = 28%), multiplie par 100 pour obtenir le pourcentage avant d'appliquer les seuils. Pour les crédits IMMOBILIER applique: DTI < 33% => GREEN, 33-40% => AMBER, >40% => RED. Calcule un `eligibilityScore` entre 0 et 100 (entier). Retourne exactement le schéma JSON demandé ci-dessous, sans texte supplémentaire. " ;

        String userPrompt = "Financials: " + financialsStr + "\nCin: " + cin + "\nCreditType: " + creditType + "\n\nRetourne uniquement un JSON de la forme:\n{\n  \"eligibility\": \"ELIGIBLE|REFUS|CONDITIONNEL\",\n  \"eligibilityScore\": <integer 0-100>,\n  \"risks\": [ { \"level\": \"HIGH|MEDIUM|LOW\", \"description\": \"...\", \"source\": \"...\" } ],\n  \"decisionRationale\": \"3-5 phrases en français expliquant la décision\"\n}\n\nConsidère les seuils BCT et la grille de scoring. DTI est un décimal, multiplie par 100 pour appliquer les seuils.";

        String fullPrompt = systemPrompt + "\n\n" + userPrompt;

        String response = groqClient.call(fullPrompt, 0.0);

        System.out.println("=== AGENT 3 RAW ===\n" + response);

        try {
            // First attempt to parse into Agent3Result directly
            Agent3Result result = groqClient.parse(response, Agent3Result.class);
            return result;
        } catch (Exception e) {
            System.out.println("=== AGENT 3 PARSE FAILED, attempting manual extraction ===");
            try {
                ObjectMapper om = groqClient.getObjectMapper();
                JsonNode root = om.readTree(response == null ? "{}" : response.trim());
                Agent3Result manual = new Agent3Result();
                manual.setEligibility(root.path("eligibility").asText(null));
                if (root.has("eligibilityScore") && root.get("eligibilityScore").isNumber()) {
                    manual.setEligibilityScore(root.get("eligibilityScore").asInt());
                } else if (root.has("eligibilityScore")) {
                    double v = root.path("eligibilityScore").asDouble(0.0);
                    manual.setEligibilityScore((int) Math.round(v));
                } else {
                    manual.setEligibilityScore(0);
                }

                List<String> risksList = new ArrayList<>();
                if (root.has("risks") && root.get("risks").isArray()) {
                    for (JsonNode n : root.get("risks")) {
                        if (n.isTextual()) risksList.add(n.asText());
                        else risksList.add(om.writeValueAsString(n));
                    }
                }
                manual.setRisks(risksList);
                manual.setDecisionRationale(root.path("decisionRationale").asText(null));
                return manual;
            } catch (Exception ex) {
                Agent3Result fallback = new Agent3Result();
                fallback.setEligibility("UNKNOWN");
                fallback.setEligibilityScore(0);
                fallback.setDecisionRationale("parsing_failed");
                return fallback;
            }
        }
    }

    private String nullSafe(Object o) { return o == null ? "null" : o.toString(); }

    private String toJsonSafe(Object o) {
        try { return groqClient.getObjectMapper().writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }
}
