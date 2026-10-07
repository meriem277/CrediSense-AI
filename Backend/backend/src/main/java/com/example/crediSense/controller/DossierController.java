package com.example.crediSense.controller;

import com.example.crediSense.Service.impl.AuditService;
import com.example.crediSense.Service.impl.NotificationDecisionService;
import com.example.crediSense.Service.impl.RapportPdfService;
import com.example.crediSense.Service.impl.ResultatEmailService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.crediSense.entity.*;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import com.example.crediSense.repository.JsonExtractionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
    private final AgentRepository agentRepository;

    private final DecisionFinaleRepository decisionFinaleRepository;
    private final JsonExtractionRepository jsonExtractionRepository;  // ✅ nouveau
    private final RapportPdfService         rapportPdfService;
    private final NotificationDecisionService notificationService;
    private final AuditService              auditService;

    private final ObjectMapper objectMapper = new ObjectMapper();     // ✅ nouveau



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

        if (!List.of("EN_ATTENTE", "EN_COURS", "APPROUVE", "REFUSE").contains(nouveauStatut)) {
            throw new RuntimeException("Statut invalide : " + nouveauStatut);
        }

        String ancienStatut = d.getStatut();
        d.setStatut(nouveauStatut);

        // ✅ Capture l'agent connecté qui effectue le changement
        String email = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();
        agentRepository.findByEmail(email).ifPresent(d::setAgentTraitant);

        if (d.getClass().getDeclaredFields().length > 0) {
            try {
                var f = d.getClass().getDeclaredField("commentaire");
                f.setAccessible(true);
                f.set(d, commentaire);
            } catch (Exception ignored) {}
        }

        dossierRepository.save(d);
        log.info("Dossier {} → statut {} (agent: {})", id, nouveauStatut, email);
        Map<String, Object> changement = new java.util.HashMap<>();
        changement.put("ancien", String.valueOf(ancienStatut));
        changement.put("nouveau", nouveauStatut);
        auditService.enregistrer(id, AuditService.STATUT_MODIFIE, null, null, null, changement);

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
        return Map.ofEntries(
                Map.entry("dossierId",    d.getId().toString()),
                Map.entry("typeCredit",   d.getTypeCredit() != null ? d.getTypeCredit() : ""),
                Map.entry("statut",       d.getStatut() != null ? d.getStatut() : ""),
                Map.entry("createdAt",    d.getCreatedAt() != null ? d.getCreatedAt().toString() : ""),
                Map.entry("clientId",     d.getClient() != null ? d.getClient().getId().toString() : ""),
                Map.entry("clientNom",    d.getClient() != null && d.getClient().getNom() != null
                        ? d.getClient().getNom() : ""),
                Map.entry("clientPrenom", d.getClient() != null && d.getClient().getPrenom() != null
                        ? d.getClient().getPrenom() : ""),
                Map.entry("clientEmail",  d.getClient() != null && d.getClient().getEmail() != null
                        ? d.getClient().getEmail() : ""),
                Map.entry("clientCin",    d.getClient() != null && d.getClient().getCin() != null
                        ? d.getClient().getCin() : ""),
                Map.entry("agentNom",     d.getAgentTraitant() != null && d.getAgentTraitant().getNom() != null
                        ? d.getAgentTraitant().getNom() : ""),
                Map.entry("montantCredit", d.getMontantCredit() != null ? d.getMontantCredit() : 0.0)
        );
    }
    @GetMapping("/{id}/fichiers")
    public ResponseEntity<List<Map<String, Object>>> getFichiersByDossier(
            @PathVariable UUID id) {

        Dossier dossier = dossierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dossier introuvable"));

        List<Map<String, Object>> fichiers = dossier.getFichiers().stream()
                .map(f -> buildFichierMap(f, dossier))
                .collect(Collectors.toList());

        return ResponseEntity.ok(fichiers);
    }

    // ✅ Nouveau — construit la réponse enrichie d'un fichier avec jsonData et
    // cinCoherent, en s'appuyant sur la dernière JsonExtraction liée à ce
    // fichier. Utilise un LinkedHashMap (et pas Map.of) car jsonData et
    // cinCoherent peuvent être null, ce que Map.of interdit.
    private Map<String, Object> buildFichierMap(Fichier f, Dossier dossier) {
        Map<String, Object> map = new LinkedHashMap<>();

        map.put("fichierId",    f.getId().toString());
        map.put("nomOriginal",  f.getNomOriginal() != null ? f.getNomOriginal() : "");
        map.put("typeDocument", f.getTypeDocument() != null ? f.getTypeDocument() : "");
        map.put("typeOriginal", f.getTypeOriginal() != null ? f.getTypeOriginal() : "");
        // Contrôle du type : null tant que le document n'a pas été classé ou si le verdict est incertain
        map.put("typeDetecte",   f.getTypeDetecte());
        map.put("confianceType", f.getConfianceType());
        map.put("typeConflit",   f.getTypeConflit());
        map.put("classification", lireClassification(f));
        map.put("cheminPdf",    f.getCheminPdf() != null ? f.getCheminPdf() : "");
        map.put("createdAt",    f.getCreatedAt() != null ? f.getCreatedAt().toString() : "");
        // « verifie » = OCR réussi ET extraction exploitable (positionné plus bas), comme dans toResponse() :
        // un document lu par l'OCR mais sans extraction ne doit pas être affiché « validé » alors que
        // le pipeline, lui, attend ses données pour autoriser l'analyse.
        map.put("verifie",      false);
        // Statut et raison de l'OCR : permet d'afficher « format non supporté », « image illisible »…
        map.put("ocrStatut",    f.getOcrResult() != null && f.getOcrResult().getStatut() != null
                ? f.getOcrResult().getStatut() : "");
        map.put("ocrErreur",    f.getOcrResult() != null && f.getOcrResult().getErreur() != null
                ? f.getOcrResult().getErreur() : "");

        // ✅ jsonData + cinCoherent — mêmes calculs que dans FichierServiceImpl.toResponse()
        map.put("jsonData", null);
        map.put("cinCoherent", null);

        try {
            List<JsonExtraction> extractions = jsonExtractionRepository.findByFichierIdOrderByCreatedAtDesc(f.getId());

            if (extractions != null && !extractions.isEmpty()) {
                JsonExtraction derniere = extractions.get(0);   // ✅ triée DESC — index 0 = la plus récente

                if (derniere.getJsonData() != null && !derniere.getJsonData().isBlank()) {
                    Map<String, Object> parsed = objectMapper.readValue(
                            derniere.getJsonData(), new TypeReference<Map<String, Object>>() {}
                    );
                    map.put("jsonData", parsed);
                    map.put("verifie", true);

                    Object cinExtraitObj = parsed.get("cin");
                    String cinExtrait = cinExtraitObj != null ? cinExtraitObj.toString() : null;
                    String cinAttendu = (dossier.getClient() != null)
                            ? dossier.getClient().getCin() : null;

                    if (cinExtrait != null && !cinExtrait.isBlank() && cinAttendu != null) {
                        boolean coherent = normaliserCin(cinExtrait).equals(normaliserCin(cinAttendu));
                        map.put("cinCoherent", coherent);
                        if (!coherent) {
                            log.warn("CIN incohérent — fichier={} ({}), extrait='{}', attendu='{}'",
                                    f.getId(), f.getTypeDocument(), cinExtrait, cinAttendu);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Impossible de lire l'extraction JSON pour fichier {}: {}",
                    f.getId(), e.getMessage());
        }

        return map;
    }

    /** Détail de la classification (méthode, mots-clés, trace de la cascade) ; null si absent ou illisible. */
    private Map<String, Object> lireClassification(Fichier f) {
        if (f.getClassificationJson() == null || f.getClassificationJson().isBlank()) return null;
        try {
            return objectMapper.readValue(f.getClassificationJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Détail de classification illisible pour fichier {} : {}", f.getId(), e.getMessage());
            return null;
        }
    }

    // ✅ Normalise un numéro CIN pour comparaison — garde uniquement les chiffres.
    private String normaliserCin(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9]", "");
    }


    // ✅ Nouveau — relit le résultat complet d'un dossier déjà analysé,
    // sans relancer l'agent. Utilisé par credit-result.ts à l'ouverture
    // d'un dossier déjà tranché (APPROUVE/REFUSE) ou simplement déjà scoré.
    @GetMapping("/{id}/resultat")
    public ResponseEntity<Map<String, Object>> getResultat(@PathVariable UUID id) {
        DecisionFinale df = decisionFinaleRepository.findByDossierId(id).orElse(null);

        if (df == null || df.getResultatComplet() == null || df.getResultatComplet().isBlank()) {
            return ResponseEntity.notFound().build();
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    df.getResultatComplet(), new TypeReference<Map<String, Object>>() {}
            );
            // L'agent voit si la réponse est partie chez le client (envoi automatique ou manuel)
            parsed.put("notification", notificationService.etatDe(df));
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            log.warn("Résultat illisible pour dossier {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }


    // ── Réponse au client : envoi à la demande de l'agent (l'envoi automatique est dans
    //    NotificationDecisionService, déclenché dès que la décision est prise) ─────────────────
    @PostMapping("/{dossierId}/send-result-email")
    public ResponseEntity<Map<String, Object>> sendResultEmail(
            @PathVariable UUID dossierId,
            @RequestBody(required = false) Map<String, Object> payload) {
        try {
            Dossier dossier = dossierRepository.findById(dossierId)
                    .orElseThrow(() -> new RuntimeException("Dossier introuvable"));
            DecisionFinale df = decisionFinaleRepository.findByDossierId(dossierId).orElse(null);

            // Le résultat enregistré fait foi : ce qui part chez le client ne dépend pas de ce que
            // le navigateur envoie. Le contenu de la requête ne sert que si rien n'est enregistré.
            Map<String, Object> resultat = resultatEnregistre(df);
            if (resultat == null) resultat = payload;
            if (resultat == null || resultat.get("eligibility") == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Aucun résultat à envoyer pour ce dossier", "success", false));
            }

            // Envoi immédiat à la demande de l'agent : l'état de la réponse et le journal sont tenus à jour
            ResultatEmailService.Envoi envoi = notificationService.envoyerManuellement(dossier, df, resultat);
            log.info("Email envoyé à {} pour dossier {} (à la demande de l'agent)", envoi.destinataire(), dossierId);

            return ResponseEntity.ok(Map.of(
                    "message", "Email envoyé avec succès à " + envoi.destinataire(),
                    "success", true
            ));

        } catch (Exception e) {
            log.error("Erreur envoi email résultat: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", String.valueOf(e.getMessage()), "success", false));
        }
    }

    // ── Validation de la réponse : « Valider et envoyer » / « Ne pas envoyer » ───────────────
    @PostMapping("/{id}/reponse/valider")
    public ResponseEntity<Map<String, Object>> validerReponse(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {

        Dossier dossier = dossierRepository.findById(id).orElse(null);
        DecisionFinale df = decisionFinaleRepository.findByDossierId(id).orElse(null);
        Map<String, Object> resultat = resultatEnregistre(df);
        if (dossier == null || df == null || resultat == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("success", false, "error", "Aucun résultat enregistré pour ce dossier"));
        }
        try {
            // La validation porte sur la décision que l'agent a lue : si elle a changé, le serveur refuse
            Map<String, Object> etat = notificationService.valider(
                    dossier, df, resultat, body != null ? body.get("decision") : null);
            boolean parti = NotificationDecisionService.ENVOYE.equals(etat.get("statut"));
            Map<String, Object> reponse = new java.util.HashMap<>();
            reponse.put("success", parti);
            reponse.put("notification", etat);
            reponse.put("message", parti ? "Réponse envoyée au client" : String.valueOf(etat.get("detail")));
            return ResponseEntity.ok(reponse);
        } catch (NotificationDecisionService.ReponseNonModifiableException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reponse/annuler")
    public ResponseEntity<Map<String, Object>> annulerReponse(@PathVariable UUID id) {
        Dossier dossier = dossierRepository.findById(id).orElse(null);
        DecisionFinale df = decisionFinaleRepository.findByDossierId(id).orElse(null);
        if (dossier == null || df == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("success", false, "error", "Aucun résultat enregistré pour ce dossier"));
        }
        try {
            Map<String, Object> etat = notificationService.annuler(dossier, df);
            return ResponseEntity.ok(Map.of("success", true, "notification", etat,
                    "message", "Envoi annulé : le client ne recevra rien pour cette décision"));
        } catch (NotificationDecisionService.ReponseNonModifiableException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ── Journal d'audit du dossier ───────────────────────────────────────────────────────────
    @GetMapping("/{id}/audit")
    public ResponseEntity<List<Map<String, Object>>> journal(@PathVariable UUID id) {
        return ResponseEntity.ok(auditService.journal(id));
    }

    // ── Rapport PDF de la décision : version « agent » (complète) ou « client » ──────────────
    @GetMapping("/{id}/rapport-pdf")
    public ResponseEntity<byte[]> rapportPdf(
            @PathVariable UUID id,
            @RequestParam(name = "version", defaultValue = "agent") String version) {

        Dossier dossier = dossierRepository.findById(id).orElse(null);
        Map<String, Object> resultat = resultatEnregistre(decisionFinaleRepository.findByDossierId(id).orElse(null));
        if (dossier == null || resultat == null) {
            return ResponseEntity.notFound().build();
        }

        RapportPdfService.Version v = "client".equalsIgnoreCase(version)
                ? RapportPdfService.Version.CLIENT : RapportPdfService.Version.AGENT;
        byte[] pdf = rapportPdfService.generer(dossier, resultat, v);
        auditService.enregistrer(id, AuditService.RAPPORT_TELECHARGE,
                String.valueOf(resultat.get("eligibility")), null, null,
                Map.of("version", v == RapportPdfService.Version.CLIENT ? "client" : "agent"));

        String reference = id.toString().substring(0, 8).toUpperCase();
        String nom = "Rapport-credit-" + reference + (v == RapportPdfService.Version.CLIENT ? "-client" : "") + ".pdf";
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + nom + "\"")
                .body(pdf);
    }

    /** Le résultat de l'analyse enregistré avec la décision (null s'il n'y en a pas ou s'il est illisible). */
    private Map<String, Object> resultatEnregistre(DecisionFinale df) {
        if (df == null || df.getResultatComplet() == null || df.getResultatComplet().isBlank()) return null;
        try {
            return objectMapper.readValue(df.getResultatComplet(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Résultat enregistré illisible : {}", e.getMessage());
            return null;
        }
    }
}