package com.example.crediSense.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.crediSense.Service.DecisionFinaleService;
import com.example.crediSense.dto.request.DecisionFinaleRequest;
import com.example.crediSense.dto.response.DecisionFinaleResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/decisions")
@RequiredArgsConstructor
public class DecisionFinaleController {

    private final DecisionFinaleService service;

    // CREATE
    @PostMapping
    public ResponseEntity<DecisionFinaleResponse> create(
            @RequestBody DecisionFinaleRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<DecisionFinaleResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    // GET BY DOSSIER (🔥 important)
    @GetMapping("/dossier/{dossierId}")
    public ResponseEntity<DecisionFinaleResponse> getByDossierId(@PathVariable UUID dossierId) {
        return ResponseEntity.ok(service.getByDossierId(dossierId));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<DecisionFinaleResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    // UPDATE
    @PutMapping("/{id}")
    public ResponseEntity<DecisionFinaleResponse> update(
            @PathVariable UUID id,
            @RequestBody DecisionFinaleRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok("Décision supprimée avec succès");
    }
}