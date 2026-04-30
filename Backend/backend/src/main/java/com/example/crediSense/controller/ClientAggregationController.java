package com.example.crediSense.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.crediSense.Service.ClientAggregationService;
import com.example.crediSense.dto.request.ClientAggregationRequest;
import com.example.crediSense.dto.response.ClientAggregationResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/client-aggregations")
@RequiredArgsConstructor
public class ClientAggregationController {

    private final ClientAggregationService service;

    // CREATE
    @PostMapping
    public ResponseEntity<ClientAggregationResponse> create(
            @RequestBody ClientAggregationRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    // UPSERT (🔥 très important pour ton IA)
    @PostMapping("/upsert")
    public ResponseEntity<ClientAggregationResponse> upsert(
            @RequestBody ClientAggregationRequest request) {
        return ResponseEntity.ok(service.upsertByCin(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<ClientAggregationResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    // GET BY CIN (🔥 clé principale métier)
    @GetMapping("/cin/{cin}")
    public ResponseEntity<ClientAggregationResponse> getByCin(@PathVariable String cin) {
        return ResponseEntity.ok(service.getByCin(cin));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<ClientAggregationResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    // UPDATE
    @PutMapping("/{id}")
    public ResponseEntity<ClientAggregationResponse> update(
            @PathVariable UUID id,
            @RequestBody ClientAggregationRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok("Aggregation supprimée avec succès");
    }
}