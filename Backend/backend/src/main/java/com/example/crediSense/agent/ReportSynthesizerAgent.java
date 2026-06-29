package com.example.crediSense.agent;

import com.example.crediSense.Service.GroqClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.crediSense.dto.response.CreditAnalysisResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReportSynthesizerAgent {

    private final GroqClient groqClient;

    public CreditAnalysisResult run(Agent1Result a1, Agent2Result a2, Agent3Result a3, String creditType) {
        String systemPrompt = "Tu es un rédacteur professionnel en français. Synthétise les résultats d'analyse en un rapport de crédit final en JSON. Retourne STRICTEMENT du JSON brut (aucun markdown, aucun bloc de code, aucune explication hors JSON).";

        String example = "Exemple de structure attendue:\n" +
                "{\n" +
                "  \"creditType\": \"IMMOBILIER\",\n" +
                "  \"eligibility\": \"ELIGIBLE\",\n" +
                "  \"eligibilityScore\": 80,\n" +
                "  \"financialMetrics\": { \"dti\": 28.0, \"ltv\": 60.0 },\n" +
                "  \"risks\": [\n" +
                "    { \"level\": \"HIGH\", \"description\": \"Le taux d'endettement futur de 28% approche le seuil BCT de 33%\", \"source\": \"Circulaire BCT n°91-24\" }\n" +
                "  ],\n" +
                "  \"recommendedPlan\": [\n" +
                "    { \"priority\": 1, \"action\": \"Fournir l'attestation de salaire originale\", \"rationale\": \"Document obligatoire pour finaliser le dossier\", \"source\": \"Procédure interne Attijariwafa Bank\" }\n" +
                "  ],\n" +
                "  \"rawExplanation\": \"Texte explicatif succinct en français...\"\n" +
                "}";

        String userPrompt = "Agent1:\n" + toJson(a1) + "\nAgent2:\n" + toJson(a2) + "\nAgent3:\n" + toJson(a3) + "\nCreditType:" + creditType + "\n\n" +
                "IMPORTANT:\n" +
                "- Retourne uniquement du JSON brut correspondant à la structure ci-après.\n" +
                "- \"eligibilityScore\" doit être un entier entre 0 et 100 (ex: 80, pas 0.8).\n" +
                "- Les champs \"risks\" et \"recommendedPlan\" doivent suivre la structure d'exemple fournie ci-dessous.\n\n" +
                example + "\n\nProduis le JSON final maintenant.";

        String response = groqClient.call(systemPrompt + "\n\n" + userPrompt, 0.2);

        System.out.println("=== AGENT 4 RAW ===\n" + response);

        try {
            // Defensive parse: read JSON tree and merge using Agent3 authoritative fields
            ObjectMapper om = groqClient.getObjectMapper();
            JsonNode root = om.readTree(response == null ? "{}" : response.trim());

            CreditAnalysisResult merged = new CreditAnalysisResult();
            merged.setCreditType(creditType);

            // Use Agent3 as authoritative for eligibility and score
            merged.setEligibility(a3.getEligibility());
            merged.setEligibilityScore(a3.getEligibilityScore() == null ? 0 : a3.getEligibilityScore());

            // Risks come from Agent3
            merged.setRisks(a3.getRisks());

            // Financial metrics from Agent2
            java.util.Map<String, Object> fm = new java.util.HashMap<>();
            fm.put("monthlyIncome", a2.getMonthlyIncome());
            fm.put("existingDebts", a2.getExistingDebts());
            fm.put("requestedAmount", a2.getRequestedAmount());
            fm.put("propertyValue", a2.getPropertyValue());
            fm.put("durationMonths", a2.getDurationMonths());
            fm.put("dti", a2.getDti());
            fm.put("ltv", a2.getLtv());
            fm.put("personalContribution", a2.getPersonalContribution());
            merged.setFinancialMetrics(fm);

            // recommendedPlan: extract from agent4 output if present, normalize to List<String>
            java.util.List<String> planList = new java.util.ArrayList<>();
            if (root.has("recommendedPlan") && root.get("recommendedPlan").isArray()) {
                for (JsonNode n : root.get("recommendedPlan")) {
                    if (n.isTextual()) planList.add(n.asText());
                    else planList.add(om.writeValueAsString(n));
                }
            }
            merged.setRecommendedPlan(planList);

            // rawExplanation from agent4 if present, else from Agent3
            if (root.has("rawExplanation")) merged.setRawExplanation(root.path("rawExplanation").asText(null));
            else merged.setRawExplanation(a3.getDecisionRationale());

            return merged;
        } catch (Exception e) {
            CreditAnalysisResult fallback = new CreditAnalysisResult();
            fallback.setCreditType(creditType);
            fallback.setEligibility(a3.getEligibility());
            fallback.setEligibilityScore(a3.getEligibilityScore() == null ? 0 : a3.getEligibilityScore());
            fallback.setRisks(a3.getRisks());
            fallback.setRawExplanation(a3.getDecisionRationale());
            fallback.setFinancialMetrics(java.util.Map.of("dti", a2.getDti(), "ltv", a2.getLtv()));
            fallback.setRecommendedPlan(java.util.List.of());
            return fallback;
        }
    }

    private String toJson(Object o) {
        try { return groqClient.getObjectMapper().writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }
}
