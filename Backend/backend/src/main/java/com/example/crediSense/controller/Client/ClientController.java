package com.example.crediSense.controller.Client;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.ClientRepository;
import com.example.crediSense.repository.DossierRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.crediSense.Service.ClientService;
import com.example.crediSense.dto.request.ClientRequest;
import com.example.crediSense.dto.response.ClientResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor
public class ClientController {
    private final ClientService clientService;
    private final DossierRepository dossierRepository;
    private final ClientRepository clientRepository;

    // CREATE
    @PostMapping
    public ResponseEntity<ClientResponse> create(@RequestBody ClientRequest request) {
        return ResponseEntity.ok(clientService.create(request));
    }

    // GET BY ID
    @GetMapping("/{id}")
    public ResponseEntity<ClientResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(clientService.getById(id));
    }

    // GET BY CIN (🔥 important métier)
    @GetMapping("/cin/{cin}")
    public ResponseEntity<ClientResponse> getByCin(@PathVariable String cin) {
        return ResponseEntity.ok(clientService.getByCin(cin));
    }

    // GET ALL
    @GetMapping
    public ResponseEntity<List<ClientResponse>> getAll() {
        return ResponseEntity.ok(clientService.getAll());
    }

    // UPDATE
    @PutMapping("/{id}")
    public ResponseEntity<ClientResponse> update(
            @PathVariable UUID id,
            @RequestBody ClientRequest request) {
        return ResponseEntity.ok(clientService.update(id, request));
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<String> delete(@PathVariable UUID id) {
        clientService.delete(id);
        return ResponseEntity.ok("Client supprimé avec succès");
    }

    @GetMapping("/historique")
    public ResponseEntity<List<Map<String, Object>>> getHistorique(
            @RequestParam String email) {

        Client client = clientRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Client introuvable"));

        List<Dossier> dossiers = dossierRepository
                .findByClient(client);

        List<Map<String, Object>> result = dossiers.stream()
                .map(d -> Map.<String, Object>of(
                        "dossierId",  d.getId().toString(),
                        "typeCredit", d.getTypeCredit() != null ? d.getTypeCredit() : "",
                        "statut",     d.getStatut() != null ? d.getStatut() : ""))
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
