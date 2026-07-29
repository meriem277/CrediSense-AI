package com.example.crediSense.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.DossierRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.crediSense.Service.DossierService;
import com.example.crediSense.dto.request.DossierRequest;
import com.example.crediSense.dto.response.DossierResponse;

import lombok.RequiredArgsConstructor;
@Slf4j
@RestController
@RequestMapping("/api/dossiers")
@CrossOrigin(origins = "http://localhost:4200")

@RequiredArgsConstructor
public class DossierController {

    private final DossierService dossierService;
    private final DossierRepository dossierRepository;

    // CREATE
    @PostMapping
    public ResponseEntity<DossierResponse> create(@RequestBody DossierRequest request) {
        return ResponseEntity.ok(dossierService.create(request));
    }
    // GET BY CLIENT (🔥 important)
    @GetMapping("/client/{clientId}")
    public ResponseEntity<List<DossierResponse>> getByClientId(@PathVariable UUID clientId) {
        return ResponseEntity.ok(dossierService.getByClientId(clientId));
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
    // ✅ Liste tous les dossiers EN_COURS pour les agents
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllDossiers() {
        List<Dossier> dossiers = dossierRepository.findAll();
        return ResponseEntity.ok(buildResponse(dossiers));
    }

    // ✅ Liste par statut
    @GetMapping("/statut/{statut}")
    public ResponseEntity<List<Map<String, Object>>> getByStatut(
            @PathVariable String statut) {
        List<Dossier> dossiers = dossierRepository.findByStatut(statut);
        return ResponseEntity.ok(buildResponse(dossiers));
    }
    // ✅ Détail d'un dossier
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable UUID id) {
        Dossier d = dossierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dossier introuvable"));
        return ResponseEntity.ok(buildSingle(d));
    }

    // ✅ Agent change le statut
    @PutMapping("/{id}/statut")
    public ResponseEntity<Map<String, Object>> updateStatut(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {

        Dossier d = dossierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dossier introuvable"));

        String nouveauStatut = body.get("statut");
        String commentaire   = body.getOrDefault("commentaire", "");

        // ✅ Statuts valides
        if (!List.of("EN_ATTENTE", "EN_COURS", "APPROUVE", "REFUSE").contains(nouveauStatut)) {
            throw new RuntimeException("Statut invalide : " + nouveauStatut);
        }

        d.setStatut(nouveauStatut);
        if (d.getClass().getDeclaredFields().length > 0) {
            try {
                var f = d.getClass().getDeclaredField("commentaire");
                f.setAccessible(true);
                f.set(d, commentaire);
            } catch (Exception ignored) {}
        }

        dossierRepository.save(d);
        log.info("Dossier {} → statut {}", id, nouveauStatut);

        return ResponseEntity.ok(Map.of(
                "dossierId", id.toString(),
                "statut",    nouveauStatut,
                "message",   "Statut mis à jour"
        ));
    }

    // ── Helpers ──────────────────────────────────────────────────────
    private List<Map<String, Object>> buildResponse(List<Dossier> dossiers) {
        return dossiers.stream().map(this::buildSingle).collect(Collectors.toList());
    }

    private Map<String, Object> buildSingle(Dossier d) {
        return Map.of(
                "dossierId",    d.getId().toString(),
                "typeCredit",   d.getTypeCredit() != null ? d.getTypeCredit() : "",
                "statut",       d.getStatut() != null ? d.getStatut() : "",
                "clientId",     d.getClient() != null ? d.getClient().getId().toString() : "",  // ✅ ajoutez
                "clientNom",    d.getClient() != null && d.getClient().getNom() != null
                        ? d.getClient().getNom() : "",
                "clientPrenom", d.getClient() != null && d.getClient().getPrenom() != null
                        ? d.getClient().getPrenom() : "",
                "clientEmail",  d.getClient() != null && d.getClient().getEmail() != null
                        ? d.getClient().getEmail() : "",
                "clientCin",    d.getClient() != null && d.getClient().getCin() != null
                        ? d.getClient().getCin() : ""
        );


    }

    @GetMapping("/{id}/fichiers")
    public ResponseEntity<List<Map<String, Object>>> getFichiersByDossier(
            @PathVariable UUID id) {

        Dossier dossier = dossierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dossier introuvable"));

        List<Map<String, Object>> fichiers = dossier.getFichiers().stream()
                .map(f -> Map.<String, Object>of(
                        "fichierId",    f.getId().toString(),
                        "nomOriginal",  f.getNomOriginal() != null ? f.getNomOriginal() : "",
                        "typeDocument", f.getTypeDocument() != null ? f.getTypeDocument() : "",
                        "typeOriginal", f.getTypeOriginal() != null ? f.getTypeOriginal() : "",
                        "cheminPdf",    f.getCheminPdf() != null ? f.getCheminPdf() : "",
                        "createdAt",    f.getCreatedAt() != null ? f.getCreatedAt().toString() : ""
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(fichiers);
    }

}