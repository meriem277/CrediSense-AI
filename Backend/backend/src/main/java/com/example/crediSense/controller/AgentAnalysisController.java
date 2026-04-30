package com.example.crediSense.controller;



import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;  
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.crediSense.Service.AgentAnalysisService;
import com.example.crediSense.dto.request.AgentAnalysisRequest;
import com.example.crediSense.dto.response.AgentAnalysisResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/agent-analyses")
@RequiredArgsConstructor
public class AgentAnalysisController {

    private final AgentAnalysisService agentAnalysisService;

    // CREATE
    @PostMapping
    public ResponseEntity<AgentAnalysisResponse> create(@RequestBody AgentAnalysisRequest request) {
        return ResponseEntity.ok(agentAnalysisService.create(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<AgentAnalysisResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(agentAnalysisService.getById(id));
    }

    // GET BY DOSSIER ID
    @GetMapping("/dossier/{dossierId}")
    public ResponseEntity<AgentAnalysisResponse> getByDossierId(@PathVariable UUID dossierId) {
        return ResponseEntity.ok(agentAnalysisService.getByDossierId(dossierId));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<AgentAnalysisResponse>> getAll() {
        return ResponseEntity.ok(agentAnalysisService.getAll());
    }

    // UPDATE
    @PutMapping("/{id}")
    public ResponseEntity<AgentAnalysisResponse> update(
            @PathVariable UUID id,
            @RequestBody AgentAnalysisRequest request) {
        return ResponseEntity.ok(agentAnalysisService.update(id, request));
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        agentAnalysisService.delete(id);
        return ResponseEntity.ok("Analyse supprimée avec succès");
    }
}