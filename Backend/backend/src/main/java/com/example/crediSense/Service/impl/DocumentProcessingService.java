package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.entity.OcrResult;
import com.example.crediSense.entity.JsonExtraction;
import com.example.crediSense.repository.FichierRepository;
import com.example.crediSense.repository.OcrResultRepository;
import com.example.crediSense.repository.JsonExtractionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final RestTemplate              restTemplate;
    private final FichierRepository         fichierRepository;
    private final OcrResultRepository       ocrResultRepository;
    private final JsonExtractionRepository  jsonExtractionRepository;
    private final ObjectMapper              objectMapper;

    @Value("${nlp.service.url}")
    private String nlpServiceUrl;

    // ── Pas de constructeur manuel ici — @RequiredArgsConstructor s'en charge ──

    // ─── Point d'entrée principal ──────────────────────────────────────────

    /**
     * Traite un fichier fraîchement uploadé : OCR -> classification ->
     * (vérification identité si CIN) -> extraction financière.
     * Met à jour Fichier.typeDocument et persiste OcrResult / JsonExtraction.
     */
    public Map<String, Object> traiterFichier(Fichier fichier, Client client) {
        Map<String, Object> resultat = new HashMap<>();

        try {
            // ── Étape 1 : OCR ────────────────────────────────────────────
            Map<String, Object> ocrResponse = appelerOcr(fichier.getCheminPdf());
            String texteOcr = (String) ocrResponse.getOrDefault("texte", "");

            OcrResult ocrResult = OcrResult.builder()
                    .fichier(fichier)
                    .texteBrut(texteOcr)
                    .texteNettoye(texteOcr)  // pas de nettoyage séparé pour l'instant — même valeur
                    .statut((String) ocrResponse.getOrDefault("statut", "UNKNOWN"))
                    .build();
            ocrResultRepository.save(ocrResult);
            resultat.put("ocr", ocrResponse);

            if (texteOcr == null || texteOcr.isBlank()) {
                log.warn("OCR vide pour fichier {} — arrêt du pipeline", fichier.getId());
                resultat.put("statut", "OCR_VIDE");
                return resultat;
            }

            // ── Étape 2 : Classification hybride ─────────────────────────
            Map<String, Object> classification = appelerClassification(
                    texteOcr, fichier.getDossier().getId().toString()
            );
            String typeDocument = (String) classification.getOrDefault("type_document", "AUTRE");

            fichier.setTypeDocument(typeDocument);
            fichierRepository.save(fichier);
            resultat.put("classification", classification);

            log.info("Fichier {} classifié -> {} (méthode={})",
                    fichier.getId(), typeDocument, classification.get("methode"));

            // ── Étape 3 : Vérification identité (uniquement si CIN) ──────
            if ("CIN".equals(typeDocument) && client != null) {
                Map<String, Object> verification = appelerVerificationIdentite(
                        texteOcr, client, fichier.getDossier().getId().toString()
                );
                resultat.put("verification_identite", verification);

                boolean identiteConfirmee = Boolean.TRUE.equals(
                        verification.get("identite_confirmee")
                );
                if (!identiteConfirmee) {
                    log.warn("ALERTE — identité non confirmée pour dossier {} : {}",
                            fichier.getDossier().getId(), verification.get("alertes"));
                }
            }

            // ── Étape 4 : Extraction financière (GROQ) ───────────────────
            Map<String, Object> extraction = appelerExtractionFinanciere(
                    texteOcr, client != null ? client.getCin() : ""
            );

            JsonExtraction jsonExtraction = JsonExtraction.builder()
                    .fichier(fichier)
                    .cin(client != null ? client.getCin() : null)
                    .jsonData(objectMapper.writeValueAsString(extraction.get("json_data")))
                    .confidenceScore(toDoubleOrNull(extraction.get("confidence_score")))
                    .build();
            jsonExtractionRepository.save(jsonExtraction);
            resultat.put("extraction", extraction);

            resultat.put("statut", "SUCCESS");
            return resultat;

        } catch (Exception e) {
            log.error("Erreur pipeline traitement fichier {} : {}", fichier.getId(), e.getMessage(), e);
            resultat.put("statut", "FAILURE");
            resultat.put("erreur", e.getMessage());
            return resultat;
        }
    }

    // ─── Appels HTTP individuels (même pattern que ChatbotService) ────────

    private Map<String, Object> appelerOcr(String pdfPath) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("pdf_path", pdfPath);
        body.put("type_original", "pdf");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        Map response = restTemplate.postForObject(
                nlpServiceUrl + "/ocr/extract", request, Map.class
        );
        return response != null ? response : Map.of();
    }

    private Map<String, Object> appelerClassification(String texte, String dossierId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("texte", texte);
        body.put("dossier_id", dossierId);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        Map response = restTemplate.postForObject(
                nlpServiceUrl + "/ai/classify-hybrid", request, Map.class
        );
        return response != null ? response : Map.of();
    }

    private Map<String, Object> appelerVerificationIdentite(String texteOcrCin, Client client, String dossierId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("texte_ocr_cin", texteOcrCin);
        body.put("nom_declare", client.getNom());
        body.put("prenom_declare", client.getPrenom());
        body.put("numero_cin_declare", client.getCin());
        body.put("dossier_id", dossierId);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        Map response = restTemplate.postForObject(
                nlpServiceUrl + "/ai/verify-identity", request, Map.class
        );
        return response != null ? response : Map.of();
    }

    private Map<String, Object> appelerExtractionFinanciere(String texteNettoye, String cin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("texte_nettoye", texteNettoye);
        body.put("cin", cin != null ? cin : "");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        Map response = restTemplate.postForObject(
                nlpServiceUrl + "/ai/extract-json", request, Map.class
        );
        return response != null ? response : Map.of();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private Double toDoubleOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
