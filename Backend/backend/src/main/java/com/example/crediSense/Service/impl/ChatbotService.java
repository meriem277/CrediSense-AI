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
    private final ObjectMapper             objectMapper;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String apiUrl;

    // ─── Point d'entrée principal ─────────────────────────────────────────────

    public String poserQuestion(String cin, UUID dossierId, String question) {
        log.info("Chatbot RAG — dossierId={}, question={}", dossierId, question);

        // 1. Construire le contexte depuis le dossier
        String contexte = construireContexte(dossierId);

        if (contexte.isBlank()) {
            return "Je n'ai pas encore de données analysées pour ce dossier. " +
                    "Veuillez d'abord uploader les documents du client.";
        }

        // 2. Appeler GROQ avec le contexte + la question
        return appelGroqChatbot(contexte, question, cin);
    }

    // ─── Construction du contexte par dossierId ───────────────────────────────

    private String construireContexte(UUID dossierId) {
        if (dossierId == null) return "";

        StringBuilder ctx = new StringBuilder();

        // 1. JsonExtraction du dossier (données structurées GROQ)
        jsonExtractionRepository.findByDossierId(dossierId).forEach(extraction -> {
            if (extraction.getJsonData() != null && !extraction.getJsonData().isBlank()) {
                ctx.append("=== DONNÉES FINANCIÈRES STRUCTURÉES ===\n");
                ctx.append(formaterJson(extraction.getJsonData()));
                ctx.append("\n\n");
            }
        });

        // 2. Texte OCR nettoyé du dossier
        ocrResultRepository.findByDossierId(dossierId).forEach(ocr -> {
            if (ocr.getTexteNettoye() != null && !ocr.getTexteNettoye().isBlank()) {
                ctx.append("=== TEXTE DU DOCUMENT ===\n");
                String texte = ocr.getTexteNettoye();
                // Limiter à 2000 chars pour ne pas dépasser le contexte GROQ
                ctx.append(texte.length() > 2000 ? texte.substring(0, 2000) + "..." : texte);
                ctx.append("\n\n");
            }
        });

        log.info("Contexte RAG construit — {} caractères pour dossierId={}",
                ctx.length(), dossierId);
        return ctx.toString();
    }

    // ─── Formatage JSON → texte lisible ──────────────────────────────────────

    private String formaterJson(String jsonData) {
        try {
            JsonNode node = objectMapper.readTree(jsonData);
            StringBuilder sb = new StringBuilder();
            node.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNull()) {
                    sb.append("- ").append(entry.getKey())
                            .append(": ").append(entry.getValue().asText())
                            .append("\n");
                }
            });
            return sb.toString();
        } catch (Exception e) {
            log.warn("JSON non parsable, retour brut : {}", e.getMessage());
            return jsonData;
        }
    }

    // ─── Appel GROQ ───────────────────────────────────────────────────────────

    private String appelGroqChatbot(String contexte, String question, String cin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String systemPrompt = """
                Tu es CrediSense, un assistant IA expert en analyse de dossiers de crédit bancaire pour Attijariwafa Bank.
                Tu réponds UNIQUEMENT en te basant sur les données du dossier fournies dans le contexte.
                Tu es précis, professionnel et concis.
                Si une information n'est pas dans le contexte, dis-le clairement.
                Réponds toujours en français.
                """;

        String userPrompt = "Contexte du dossier client (CIN: " + cin + ") :\n\n"
                + contexte
                + "\nQuestion de l'agent : " + question;

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)
                ),
                "temperature", 0.3,
                "max_tokens",  500
        );

        try {
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);

            JsonNode root    = objectMapper.readTree(response.getBody());
            String   content = root.path("choices").get(0)
                    .path("message").path("content").asText();

            log.info("Chatbot répondu — {} caractères", content.length());
            return content;

        } catch (Exception e) {
            log.error("Erreur chatbot GROQ : {}", e.getMessage());
            return "Désolé, une erreur est survenue lors de la génération de la réponse.";
        }
    }
}