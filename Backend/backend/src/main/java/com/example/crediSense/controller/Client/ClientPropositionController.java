package com.example.crediSense.controller.Client;

import com.example.crediSense.Service.impl.PropositionClientService;
import com.example.crediSense.Service.impl.PropositionClientService.PropositionException;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.ClientRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Propositions d'ajustement d'un dossier conditionnel, vues et acceptées par le CLIENT connecté.
 *
 * Réservé au rôle CLIENT (voir SecurityConfig) et limité à ses propres dossiers : un dossier qui n'est pas
 * le sien répond « introuvable », comme s'il n'existait pas.
 */
@Slf4j
@RestController
@RequestMapping("/api/clients/mes-demandes")
@RequiredArgsConstructor
public class ClientPropositionController {

    private final PropositionClientService propositionService;
    private final ClientRepository clientRepository;
    private final DossierRepository dossierRepository;
    private final DecisionFinaleRepository decisionFinaleRepository;

    public record ReponseRequest(String choix, Integer offre) {}

    /** Les demandes du client qui ont une proposition : {dossierId, etat}. */
    @GetMapping("/propositions")
    public ResponseEntity<List<Map<String, Object>>> mesPropositions(Authentication authentication) {
        return clientConnecte(authentication)
                .map(client -> ResponseEntity.ok(propositionService.propositionsDuClient(client)))
                .orElseGet(() -> ResponseEntity.status(403).build());
    }

    @GetMapping("/{dossierId}/proposition")
    public ResponseEntity<?> proposition(@PathVariable UUID dossierId, Authentication authentication) {
        Dossier dossier = dossierDuClient(dossierId, authentication);
        if (dossier == null) return introuvable();

        return decisionFinaleRepository.findByDossierId(dossierId)
                .flatMap(propositionService::propositionPour)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(this::introuvable);
    }

    @PostMapping("/{dossierId}/proposition/repondre")
    public ResponseEntity<?> repondre(@PathVariable UUID dossierId,
                                      @RequestBody ReponseRequest corps,
                                      Authentication authentication) {
        Dossier dossier = dossierDuClient(dossierId, authentication);
        if (dossier == null) return introuvable();

        DecisionFinale df = decisionFinaleRepository.findByDossierId(dossierId).orElse(null);
        if (df == null) return introuvable();

        try {
            Map<String, Object> vue = propositionService.repondre(
                    dossier, df, corps == null ? null : corps.choix(), corps == null ? null : corps.offre());
            return ResponseEntity.ok(vue);
        } catch (PropositionException e) {
            return ResponseEntity.status(e.getStatut()).body(Map.of("message", e.getMessage()));
        }
    }

    // ── Outils ────────────────────────────────────────────────────────────────

    private java.util.Optional<Client> clientConnecte(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) return java.util.Optional.empty();
        return clientRepository.findByEmail(authentication.getName());
    }

    /** Le dossier demandé, seulement s'il appartient au client connecté. */
    private Dossier dossierDuClient(UUID dossierId, Authentication authentication) {
        Client client = clientConnecte(authentication).orElse(null);
        if (client == null) return null;
        Dossier dossier = dossierRepository.findById(dossierId).orElse(null);
        if (dossier == null || dossier.getClient() == null || dossier.getClient().getId() == null
                || !dossier.getClient().getId().equals(client.getId())) {
            return null;
        }
        return dossier;
    }

    private ResponseEntity<Map<String, String>> introuvable() {
        return ResponseEntity.status(404).body(Map.of("message", "Aucune proposition pour cette demande."));
    }
}
