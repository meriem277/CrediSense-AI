package com.example.crediSense.controller;

import com.example.crediSense.Service.FichierService;
import com.example.crediSense.dto.response.FichierResponse;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.repository.DossierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/fichiers")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class FichierController {

    private final FichierService    fichierService;
    private final DossierRepository dossierRepository;
    private final RestTemplate      restTemplate;

    @Value("${nlp.service.url}")
    private String nlpServiceUrl;

    // ── Upload ────────────────────────────────────────────────────────
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FichierResponse> upload(
            @RequestParam("file")      MultipartFile file,
            @RequestParam("cin")       String cin,
            @RequestParam("agentId")   UUID agentId,
            @RequestParam("dossierId") UUID dossierId) {
        return ResponseEntity.ok(
                fichierService.uploadAndConvert(file, cin, agentId, dossierId)
        );
    }

    // ── CRUD ──────────────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<FichierResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(fichierService.getById(id));
    }

    @GetMapping("/agent/{agentId}")
    public ResponseEntity<List<FichierResponse>> getByAgentId(
            @PathVariable UUID agentId) {
        return ResponseEntity.ok(fichierService.getByAgentId(agentId));
    }

    @GetMapping("/cin/{cin}")
    public ResponseEntity<List<FichierResponse>> getByCin(
            @PathVariable String cin) {
        return ResponseEntity.ok(fichierService.getByCin(cin));
    }

    @GetMapping
    public ResponseEntity<List<FichierResponse>> getAll() {
        return ResponseEntity.ok(fichierService.getAll());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        fichierService.delete(id);
        return ResponseEntity.ok("Fichier supprimé avec succès");
    }

    // ── View fichier ──────────────────────────────────────────────────
    @GetMapping("/view/**")
    public ResponseEntity<Resource> viewFile(
            HttpServletRequest request) throws IOException {
        String uri  = request.getRequestURI();
        String path = uri.substring(uri.indexOf("/view/") + 6);

        Path     filePath = Paths.get(path).normalize();
        Resource resource = new FileSystemResource(filePath);

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        String contentType = Files.probeContentType(filePath);
        if (contentType == null) contentType = "application/octet-stream";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    // ── Analyse dossier ───────────────────────────────────────────────
    @PostMapping("/analyser-dossier/{dossierId}")
    public ResponseEntity<Map<String, Object>> analyserDossier(
            @PathVariable UUID dossierId) {
        try {
            List<Fichier> fichiers = fichierService.getByDossierId(dossierId);

            if (fichiers == null || fichiers.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "message", "Aucun fichier à analyser",
                        "success", false
                ));
            }

            String cin = fichiers.get(0).getCin() != null
                    ? fichiers.get(0).getCin() : "";

            // ✅ Appelle le pipeline et récupère le score
            Map scoreResult = fichierService.analyserEtScorer(cin, dossierId.toString());

            Map<String, Object> response = new HashMap<>();
            response.put("message",     fichiers.size() + " fichier(s) analysé(s)");
            response.put("success",     true);
            response.put("total",       fichiers.size());
            response.put("scoreResult", scoreResult);  // ✅ retourne le score

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Erreur analyse dossier {}: {}", dossierId, e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "message", "Erreur : " + e.getMessage(),
                    "success", false
            ));
        }
    }
}