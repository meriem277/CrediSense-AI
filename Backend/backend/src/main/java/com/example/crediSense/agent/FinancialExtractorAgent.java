package com.example.crediSense.agent;

import com.example.crediSense.Service.GroqClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FinancialExtractorAgent {

    private final GroqClient groqClient;

    public Agent2Result run(Agent1Result doc, String creditType) {
        String systemPrompt = "Tu es un expert en extraction financière pour dossiers de crédit. Extrait toutes les valeurs numériques exactement comme écrites et calcule DTI et LTV si possible. Retourne JSON.";

        String documentText = (doc == null) ? null : doc.getCleanText();
        if (documentText == null || documentText.isBlank()) {
            System.out.println("=== AGENT 2 WARNING: document cleanText was null or empty ===");
            Agent2Result empty = new Agent2Result();
            empty.setMissingFields(java.util.List.of("ALL - document text empty"));
            return empty;
        }

        System.out.println("=== AGENT 2: documentText length = " + documentText.length());

        String userPrompt = "=== DOCUMENT TEXT START ===\n" + documentText + "\n=== DOCUMENT TEXT END ===\n\nType de crédit: " + creditType + "\n\nRetourne un JSON correspondant aux champs: monthlyIncome, existingDebts, requestedAmount, propertyValue, durationMonths, dti, ltv, personalContribution, employmentType, employmentYears, hasCreditIncidents, creditPurpose, missingFields.";

        String response = groqClient.call(systemPrompt + "\n\n" + userPrompt, 0.0);

        System.out.println("=== AGENT 2 RAW ===\n" + response);

        try {
            Agent2Result result = groqClient.parse(response, Agent2Result.class);
            return result;
        } catch (Exception e) {
            Agent2Result fallback = new Agent2Result();
            fallback.setMissingFields(java.util.List.of("parsing_failed"));
            return fallback;
        }
    }
}
