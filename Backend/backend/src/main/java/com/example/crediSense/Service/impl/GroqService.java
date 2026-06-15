package com.example.crediSense.Service.impl;

import com.example.crediSense.Service.JsonExtractionService;
import com.example.crediSense.Service.OcrResultService;
import com.example.crediSense.dto.request.JsonExtractionRequest;
import com.example.crediSense.dto.response.JsonExtractionResponse;
import com.example.crediSense.dto.response.OcrResultResponse;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.repository.FichierRepository;
import com.example.crediSense.repository.OcrResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroqService {

    private final RestTemplate           restTemplate;
    private final FichierRepository      fichierRepository;
    private final OcrResultRepository    ocrResultRepository;
    private final JsonExtractionService  jsonExtractionService;
    private final ObjectMapper           objectMapper;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.model:mixtral-8x7b-32768}")
    private String model;

    @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String apiUrl;

    // ─── Point d'entrée principal ─────────────────────────────────────────────

    /**
     * Appelle GROQ pour extraire les données structurées du texte OCR nettoyé.
     * Sauvegarde le résultat dans JsonExtraction en base.
     *
     * @param fichierId UUID du fichier traité
     * @return JsonExtractionResponse sauvegardé
     */
    public JsonExtractionResponse extraireJson(UUID fichierId) {
        log.info("=== Démarrage GROQ extraction — fichierId={} ===", fichierId);

        // 1. Récupérer le fichier
        Fichier fichier = fichierRepository.findById(fichierId)
                .orElseThrow(() -> new RuntimeException("Fichier introuvable : " + fichierId));

        // 2. Récupérer le texte nettoyé depuis OcrResult
        String texteNettoye = ocrResultRepository.findByFichierId(fichierId)
                .map(ocr -> ocr.getTexteNettoye())
                .orElseThrow(() -> new RuntimeException("OcrResult introuvable pour fichierId : " + fichierId));

        if (texteNettoye == null || texteNettoye.isBlank()) {
            throw new RuntimeException("Texte OCR vide — impossible d'extraire le JSON");
        }

        // 3. Appeler GROQ
        String jsonBrut = appelGroq(texteNettoye);
        log.info("GROQ a retourné : {} caractères", jsonBrut.length());

        // 4. Nettoyer et valider le JSON retourné
        String jsonPropre = nettoyerJson(jsonBrut);

        // 5. Calculer un score de confiance simple
        double confidence = calculerConfidence(jsonPropre);

        // 6. Sauvegarder dans JsonExtraction
        JsonExtractionRequest request = new JsonExtractionRequest();
        request.setCin(fichier.getCin());
        request.setJsonData(jsonPropre);
        request.setConfidenceScore(confidence);
        request.setFichierId(fichierId);

        JsonExtractionResponse result = jsonExtractionService.create(request);
        log.info("GROQ extraction terminée — JsonExtraction id={}", result.getId());

        return result;
    }

    // ─── Appel API GROQ ───────────────────────────────────────────────────────

    private String appelGroq(String texte) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // Prompt d'extraction structurée
        String systemPrompt = """
                Tu es un expert en analyse de dossiers de crédit bancaire.
                Ton rôle est d'extraire les informations financières clés du texte fourni.
                Tu dois répondre UNIQUEMENT avec un objet JSON valide, sans texte avant ou après.
                Si une information est absente du texte, utilise null pour ce champ.
                """;

        String userPrompt = """
                Extrais les informations suivantes du document bancaire ci-dessous et retourne un JSON valide :
                
                {
                  "nomClient": "string",
                  "prenomClient": "string",
                  "cin": "string",
                  "revenuMensuelNet": number,
                  "typeContrat": "CDI|CDD|FONCTIONNAIRE|INDEPENDANT|RETRAITE",
                  "employeur": "string",
                  "anciennete": "string",
                  "chargesMenusuelles": number,
                  "tauxEndettement": number,
                  "montantCredit": number,
                  "dureeCredit": number,
                  "typeCredit": "IMMOBILIER|CONSOMMATION",
                  "soldeMoyenCompte": number,
                  "historiqueCredit": "BON|MOYEN|MAUVAIS",
                  "incidentsPayment": number
                }
                
                Document à analyser :
                """ + texte;

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)
                ),
                "temperature", 0.1,
                "max_tokens",  1000
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("GROQ API erreur : " + response.getStatusCode());
            }

            // Parser la réponse GROQ
            JsonNode root    = objectMapper.readTree(response.getBody());
            String   content = root.path("choices").get(0).path("message").path("content").asText();

            log.info("GROQ réponse reçue — modèle={}", root.path("model").asText());
            return content;

        } catch (Exception e) {
            log.error("Erreur appel GROQ : {}", e.getMessage());
            throw new RuntimeException("Erreur GROQ : " + e.getMessage(), e);
        }
    }

    // ─── Nettoyage du JSON retourné ───────────────────────────────────────────

    private String nettoyerJson(String texte) {
        try {
            // Supprimer les balises ```json ... ``` si présentes
            String propre = texte.strip();
            if (propre.startsWith("```json")) {
                propre = propre.substring(7);
            } else if (propre.startsWith("```")) {
                propre = propre.substring(3);
            }
            if (propre.endsWith("```")) {
                propre = propre.substring(0, propre.length() - 3);
            }
            propre = propre.strip();

            // Valider que c'est un JSON valide
            objectMapper.readTree(propre);
            return propre;

        } catch (Exception e) {
            log.warn("JSON GROQ invalide, retour du texte brut : {}", e.getMessage());
            return texte;
        }
    }

    // ─── Calcul confiance simple ──────────────────────────────────────────────

    private double calculerConfidence(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            long total    = node.size();
            long remplis  = 0;

            var iter = node.fields();
            while (iter.hasNext()) {
                var entry = iter.next();
                if (!entry.getValue().isNull()) remplis++;
            }

            double score = total > 0 ? (double) remplis / total : 0.0;
            log.info("Score confiance : {}/{} = {}", remplis, total, score);
            return Math.round(score * 100.0) / 100.0;

        } catch (Exception e) {
            return 0.5;
        }
    }
}