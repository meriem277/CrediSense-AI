package com.example.crediSense.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.crediSense.Service.OcrResultService;
import com.example.crediSense.dto.request.OcrResultRequest;
import com.example.crediSense.dto.response.OcrResultResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ocr-results")
@RequiredArgsConstructor
public class OcrResultController {

    private final OcrResultService service;

    // CREATE
    @PostMapping
    public ResponseEntity<OcrResultResponse> create(@RequestBody OcrResultRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<OcrResultResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    // GET BY FICHIER (🔥 important)
    @GetMapping("/fichier/{fichierId}")
    public ResponseEntity<OcrResultResponse> getByFichierId(@PathVariable UUID fichierId) {
        return ResponseEntity.ok(service.getByFichierId(fichierId));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<OcrResultResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    // UPDATE
    @PutMapping("/{id}")
    public ResponseEntity<OcrResultResponse> update(
            @PathVariable UUID id,
            @RequestBody OcrResultRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok("OCR supprimé avec succès");
    }
}