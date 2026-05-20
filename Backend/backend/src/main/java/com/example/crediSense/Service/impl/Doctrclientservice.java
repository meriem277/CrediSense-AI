package com.example.crediSense.Service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;

/**
 * Appelle le service Python Doctr via HTTP multipart.
 * Le service Python tourne sur http://localhost:8000
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Doctrclientservice {

    private final RestTemplate restTemplate;

    @Value("${doctr.service.url:http://localhost:8000}")
    private String doctrUrl;

    // ─── DTO de réponse du service Python ─────────────────────────────────────

    public static class DoctrResponse {
        public String texte;
        public int nbPages;
        public Double confidence;
        public String statut;
        public String erreur;
    }

    // ─── Appel OCR ────────────────────────────────────────────────────────────

    /**
     * Envoie un fichier PDF au service Doctr Python et retourne le texte OCR.
     *
     * @param pdfPath chemin du fichier PDF à analyser
     * @return DoctrResponse contenant le texte et le score de confiance
     */
    public DoctrResponse callOcr(Path pdfPath) {
        log.info("Appel Doctr OCR pour : {}", pdfPath.getFileName());

        try {
            // Vérifier que le service Python est disponible
            verifierSante();

            // Construire la requête multipart
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(pdfPath.toFile()));

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<DoctrResponse> response = restTemplate.postForEntity(
                    doctrUrl + "/ocr",
                    request,
                    DoctrResponse.class
            );

            DoctrResponse result = response.getBody();

            if (result == null || "FAILURE".equals(result.statut)) {
                String erreur = result != null ? result.erreur : "Réponse nulle";
                log.error("Doctr a retourné une erreur : {}", erreur);
                throw new RuntimeException("Erreur OCR Doctr : " + erreur);
            }

            log.info("OCR réussi : {} pages, confidence={}", result.nbPages, result.confidence);
            return result;

        } catch (Exception e) {
            log.error("Erreur lors de l'appel Doctr : {}", e.getMessage());
            throw new RuntimeException("Service OCR indisponible : " + e.getMessage(), e);
        }
    }

    // ─── Vérification santé ───────────────────────────────────────────────────

    private void verifierSante() {
        try {
            ResponseEntity<String> health = restTemplate.getForEntity(
                    doctrUrl + "/health", String.class
            );
            if (!health.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Service Doctr non disponible");
            }
        } catch (Exception e) {
            throw new RuntimeException("Impossible de joindre le service Doctr sur " + doctrUrl, e);
        }
    }
}
