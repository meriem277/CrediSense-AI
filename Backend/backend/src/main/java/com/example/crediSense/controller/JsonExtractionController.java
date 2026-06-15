package com.example.crediSense.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.crediSense.Service.JsonExtractionService;
import com.example.crediSense.dto.request.JsonExtractionRequest;
import com.example.crediSense.dto.response.JsonExtractionResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/json-extractions")
@CrossOrigin(origins = "http://localhost:4200")

@RequiredArgsConstructor
public class JsonExtractionController {

    private final JsonExtractionService service;

    // CREATE
    @PostMapping
    public ResponseEntity<JsonExtractionResponse> create(
            @RequestBody JsonExtractionRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<JsonExtractionResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    // GET BY FICHIER
    @GetMapping("/fichier/{fichierId}")
    public ResponseEntity<List<JsonExtractionResponse>> getByFichierId(@PathVariable UUID fichierId) {
        return ResponseEntity.ok(service.getByFichierId(fichierId));
    }

    // GET BY CIN (🔥 important)
    @GetMapping("/cin/{cin}")
    public ResponseEntity<List<JsonExtractionResponse>> getByCin(@PathVariable String cin) {
        return ResponseEntity.ok(service.getByCin(cin));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<JsonExtractionResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok("Extraction supprimée avec succès");
    }
}