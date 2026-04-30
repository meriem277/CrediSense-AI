package com.example.crediSense.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.crediSense.Service.FichierService;
import com.example.crediSense.dto.request.FichierRequest;
import com.example.crediSense.dto.response.FichierResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/fichiers")
@RequiredArgsConstructor
public class FichierController {

    private final FichierService fichierService;

    // CREATE
    @PostMapping
    public ResponseEntity<FichierResponse> create(@RequestBody FichierRequest request) {
        return ResponseEntity.ok(fichierService.create(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<FichierResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(fichierService.getById(id));
    }

    // GET BY AGENT
    @GetMapping("/agent/{agentId}")
    public ResponseEntity<List<FichierResponse>> getByAgentId(@PathVariable UUID agentId) {
        return ResponseEntity.ok(fichierService.getByAgentId(agentId));
    }

    // GET BY CIN (🔥 très important)
    @GetMapping("/cin/{cin}")
    public ResponseEntity<List<FichierResponse>> getByCin(@PathVariable String cin) {
        return ResponseEntity.ok(fichierService.getByCin(cin));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<FichierResponse>> getAll() {
        return ResponseEntity.ok(fichierService.getAll());
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        fichierService.delete(id);
        return ResponseEntity.ok("Fichier supprimé avec succès");
    }
}