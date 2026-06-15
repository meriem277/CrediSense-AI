package com.example.crediSense.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.example.crediSense.Service.FichierService;
import com.example.crediSense.dto.response.FichierResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/fichiers")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200")
public class FichierController {

    private final FichierService fichierService;

    // ─── UPLOAD + CONVERSION PDF ──────────────────────────────────────────────


    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FichierResponse> upload(
            @RequestParam("file")    MultipartFile file,
            @RequestParam("cin")     String cin,
            @RequestParam("agentId") UUID agentId ,
            @RequestParam("dossierId") UUID dossierId
    )
    {

        return ResponseEntity.ok(fichierService.uploadAndConvert(file, cin, agentId, dossierId));
    }

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<FichierResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(fichierService.getById(id));
    }

    @GetMapping("/agent/{agentId}")
    public ResponseEntity<List<FichierResponse>> getByAgentId(@PathVariable UUID agentId) {
        return ResponseEntity.ok(fichierService.getByAgentId(agentId));
    }

    @GetMapping("/cin/{cin}")
    public ResponseEntity<List<FichierResponse>> getByCin(@PathVariable String cin) {
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
}