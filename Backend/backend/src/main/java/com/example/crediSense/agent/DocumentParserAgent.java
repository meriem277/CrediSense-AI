package com.example.crediSense.agent;

import com.example.crediSense.Service.GroqClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentParserAgent {

    private final GroqClient groqClient;

    public Agent1Result run(String rawText) {
        String systemPrompt = "Tu es un expert en traitement documentaire bancaire. Detecte la langue (fr/ar/mixed), classifie le type de document (fiche_paie, releve_bancaire, contrat_travail, attestation_salaire, compromis_vente, declaration_fiscale, dossier_credit, unknown) et liste les sections détectées. Répond strictement en JSON avec les champs demandés. NE RENVOIE PAS le texte nettoyé du document; laisse le champ cleanText vide (\"\").";

        String truncated = (rawText == null) ? "" : rawText.substring(0, Math.min(rawText.length(), 2000)).trim();

        String userPrompt = "DOCUMENT PREVIEW:\n" + truncated + "\n\nRetourne un JSON avec les champs: cleanText (\"\"), detectedLanguage, documentType, detectedSections (array).";

        String response = groqClient.call(systemPrompt + "\n\n" + userPrompt, 0.0);

        System.out.println("=== AGENT 1 RAW ===\n" + response);

        try {
            Agent1Result result = groqClient.parse(response, Agent1Result.class);
            if (result == null) result = new Agent1Result();
            // Ensure cleanText exists and is empty - pipeline will overwrite with rawText
            result.setCleanText("");
            return result;
        } catch (Exception e) {
            Agent1Result fallback = new Agent1Result();
            fallback.setCleanText("");
            fallback.setDetectedLanguage("unknown");
            fallback.setDocumentType("unknown");
            System.out.println("=== AGENT 1 PARSE FAILED - returning fallback. Raw length=" + ((rawText==null)?0:rawText.length()));
            return fallback;
        }
    }
}
