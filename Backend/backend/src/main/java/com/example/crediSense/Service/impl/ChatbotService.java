package com.example.crediSense.Service.impl;

import com.example.crediSense.repository.JsonExtractionRepository;
import com.example.crediSense.repository.OcrResultRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatbotService {

    private final RestTemplate             restTemplate;
    private final JsonExtractionRepository jsonExtractionRepository;
    private final OcrResultRepository      ocrResultRepository;
    private final NlpClientService      nlpClientService;

    private final ObjectMapper             objectMapper;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String apiUrl;
    @Value("${nlp.service.url}")  // ✅ ajoutez
    private String nlpServiceUrl;

    // ─── Point d'entrée ───────────────────────────────────────────────────────


    // Mémoire de conversation : on ne garde que les derniers messages, courts, de rôle connu.
    // Le service IA refait ce nettoyage (il ne fait pas confiance à ce qu'il reçoit) ; le faire
    // ici évite surtout d'envoyer inutilement un historique énorme sur le réseau interne.
    static final int HISTORIQUE_MAX_MESSAGES = 6;
    static final int HISTORIQUE_MAX_CHARS    = 600;

    public String poserQuestion(String cin, UUID dossierId, String question) {
        return poserQuestion(cin, dossierId, question, null);
    }

    public String poserQuestion(String cin, UUID dossierId, String question,
                                List<Map<String, String>> historique) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            body.put("question",   question);
            body.put("dossier_id", dossierId != null ? dossierId.toString() : "");  // ✅ UUID → String
            body.put("cin",        cin != null ? cin : "");
            body.put("historique", nettoyerHistorique(historique));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            Map response = restTemplate.postForObject(
                    nlpServiceUrl + "/ai/chat",
                    request, Map.class
            );

            if (response != null && response.containsKey("reponse")) {
                return response.get("reponse").toString();
            }
            return "Aucune réponse disponible.";

        } catch (Exception e) {
            log.error("Erreur chatbot: {}", e.getMessage());
            return "Erreur lors de la communication avec le chatbot IA.";
        }
    }

    /** Derniers messages « user » / « assistant » non vides, tronqués ; jamais null. */
    static List<Map<String, String>> nettoyerHistorique(List<Map<String, String>> historique) {
        List<Map<String, String>> propres = new ArrayList<>();
        if (historique == null) return propres;

        for (Map<String, String> message : historique) {
            if (message == null) continue;
            String role    = message.get("role");
            String contenu = message.get("content");
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            if (contenu == null || contenu.isBlank()) continue;

            contenu = contenu.strip();
            if (contenu.length() > HISTORIQUE_MAX_CHARS) {
                contenu = contenu.substring(0, HISTORIQUE_MAX_CHARS);
            }
            propres.add(Map.of("role", role, "content", contenu));
        }
        int debut = Math.max(0, propres.size() - HISTORIQUE_MAX_MESSAGES);
        return new ArrayList<>(propres.subList(debut, propres.size()));
    }

    private String construireContexte(UUID dossierId) {
        if (dossierId == null) return "";

        StringBuilder ctx = new StringBuilder();

        // 1. JSON structuré — champs essentiels seulement
        jsonExtractionRepository.findByDossierId(dossierId).stream()
                .findFirst()  // ← un seul JsonExtraction suffit
                .ifPresent(extraction -> {
                    if (extraction.getJsonData() != null) {
                        ctx.append("DONNÉES FINANCIÈRES:\n");
                        ctx.append(formaterJson(extraction.getJsonData()));
                        ctx.append("\n");
                    }
                });

        // 2. Texte OCR — limité à 500 chars max
        ocrResultRepository.findByDossierId(dossierId).stream()
                .findFirst()  // ← un seul OcrResult suffit
                .ifPresent(ocr -> {
                    if (ocr.getTexteNettoye() != null && !ocr.getTexteNettoye().isBlank()) {
                        String texte = ocr.getTexteNettoye();
                        ctx.append("EXTRAIT DOCUMENT:\n");
                        ctx.append(texte.length() > 500 ? texte.substring(0, 500) + "..." : texte);
                        ctx.append("\n");
                    }
                });

        log.info("Contexte RAG — {} caractères pour dossierId={}", ctx.length(), dossierId);
        return ctx.toString();
    }

    // ─── Formatage JSON — champs essentiels uniquement ────────────────────────

    private String formaterJson(String jsonData) {
        try {
            JsonNode node = objectMapper.readTree(jsonData);
            StringBuilder sb = new StringBuilder();

            // Seulement les champs les plus importants
            List<String> champs = List.of(
                    "nomClient", "prenomClient", "revenuMensuelNet",
                    "typeContrat", "tauxEndettement", "chargesMenusuelles",
                    "montantCredit", "dureeCredit", "typeCredit",
                    "historiqueCredit", "incidentsPayment"
            );

            champs.forEach(key -> {
                JsonNode val = node.get(key);
                if (val != null && !val.isNull()) {
                    sb.append("- ").append(key).append(": ")
                            .append(val.asText()).append("\n");
                }
            });

            return sb.toString();

        } catch (Exception e) {
            return jsonData.substring(0, Math.min(jsonData.length(), 300));
        }
    }

    // ─── Appel GROQ avec contexte réduit ─────────────────────────────────────

    private String appelGroq(String contexte, String question, String cin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // System prompt court
        String systemPrompt =
                "Tu es CrediSense, assistant IA pour Attijari Bank. " +
                        "Réponds uniquement en te basant sur le contexte fourni. " +
                        "Sois concis et professionnel. Réponds en français.";

        // User prompt avec contexte réduit
        String userPrompt = "Contexte (CIN: " + cin + "):\n" + contexte +
                "\nQuestion: " + question;

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)
                ),
                "temperature", 0.3,
                "max_tokens",  300    // ← réduit à 300
        );

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            JsonNode root    = objectMapper.readTree(response.getBody());
            String   content = root.path("choices").get(0)
                    .path("message").path("content").asText();

            log.info("Chatbot GROQ répondu — {} caractères", content.length());
            return content;

        } catch (Exception e) {
            log.error("Erreur chatbot GROQ : {}", e.getMessage());
            return "Désolé, une erreur est survenue. Veuillez réessayer.";
        }
    }
}