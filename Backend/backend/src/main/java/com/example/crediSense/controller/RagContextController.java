package com.example.crediSense.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.crediSense.Service.RagContextService;
import com.example.crediSense.dto.request.RagContextRequest;
import com.example.crediSense.dto.response.RagContextResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/rag-contexts")
@RequiredArgsConstructor
public class RagContextController {

    private final RagContextService service;

    // CREATE
    @PostMapping
    public ResponseEntity<RagContextResponse> create(@RequestBody RagContextRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<RagContextResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<RagContextResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    // GET BY CIN (🔥 très important)
    @GetMapping("/cin/{cin}")
    public ResponseEntity<List<RagContextResponse>> getByCin(@PathVariable String cin) {
        return ResponseEntity.ok(service.getByCin(cin));
    }

    // GET BY DOSSIER
    @GetMapping("/dossier/{dossierId}")
    public ResponseEntity<List<RagContextResponse>> getByDossier(@PathVariable UUID dossierId) {
        return ResponseEntity.ok(service.getByDossier(dossierId));
    }

    // UPDATE
    @PutMapping("/{id}")
    public ResponseEntity<RagContextResponse> update(
            @PathVariable UUID id,
            @RequestBody RagContextRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok("Contexte supprimé avec succès");
    }

    // 🔥 BUILD CONTEXT (ULTRA IMPORTANT POUR IA)
    @GetMapping("/build/{cin}")
    public ResponseEntity<String> buildContext(@PathVariable String cin) {
        return ResponseEntity.ok(service.buildContextForCin(cin));
    }
}