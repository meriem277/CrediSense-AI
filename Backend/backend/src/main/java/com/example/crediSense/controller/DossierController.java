package com.example.crediSense.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.crediSense.Service.DossierService;
import com.example.crediSense.dto.request.DossierRequest;
import com.example.crediSense.dto.response.DossierResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/dossiers")
@CrossOrigin(origins = "http://localhost:4200")

@RequiredArgsConstructor
public class DossierController {

    private final DossierService dossierService;

    // CREATE
    @PostMapping
    public ResponseEntity<DossierResponse> create(@RequestBody DossierRequest request) {
        return ResponseEntity.ok(dossierService.create(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<DossierResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(dossierService.getById(id));
    }

    // GET BY CLIENT (🔥 important)
    @GetMapping("/client/{clientId}")
    public ResponseEntity<List<DossierResponse>> getByClientId(@PathVariable UUID clientId) {
        return ResponseEntity.ok(dossierService.getByClientId(clientId));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<DossierResponse>> getAll() {
        return ResponseEntity.ok(dossierService.getAll());
    }

    // UPDATE
    @PutMapping("/{id}")
    public ResponseEntity<DossierResponse> update(
            @PathVariable UUID id,
            @RequestBody DossierRequest request) {
        return ResponseEntity.ok(dossierService.update(id, request));
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        dossierService.delete(id);
        return ResponseEntity.ok("Dossier supprimé avec succès");
    }
}