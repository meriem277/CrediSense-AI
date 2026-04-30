package com.example.crediSense.controller;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.crediSense.Service.AgentService;
import com.example.crediSense.dto.request.AgentRequest;
import com.example.crediSense.dto.response.AgentResponse;

import io.swagger.v3.oas.annotations.parameters.RequestBody;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentControlleur {
     private final AgentService agentService;

    // CREATE
    @PostMapping
    public ResponseEntity<AgentResponse> create(@RequestBody AgentRequest request) {
        return ResponseEntity.ok(agentService.create(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<AgentResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(agentService.getById(id));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<AgentResponse>> getAll() {
        return ResponseEntity.ok(agentService.getAll());
    }
     // UPDATE
    @PutMapping("/{id}")
    public ResponseEntity<AgentResponse> update(
            @PathVariable UUID id,
            @RequestBody AgentRequest request) {
        return ResponseEntity.ok(agentService.update(id, request));
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        agentService.delete(id);
        return ResponseEntity.ok("Agent supprimé avec succès");
    }
    
}
