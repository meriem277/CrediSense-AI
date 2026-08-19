package com.example.crediSense.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
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
    private final JavaMailSender mailSender;



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
                        ? d.getAgentTraitant().getNom() : "")
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
                        "createdAt",    f.getCreatedAt() != null ? f.getCreatedAt().toString() : "",
                        "verifie",      f.getOcrResult() != null
                                && "SUCCESS".equals(f.getOcrResult().getStatut())
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(fichiers);
    }


    @Value("${spring.mail.username}")
    private String fromEmail;

    @PostMapping("/{dossierId}/send-result-email")
    public ResponseEntity<Map<String, Object>> sendResultEmail(
            @PathVariable UUID dossierId,
            @RequestBody Map<String, Object> payload) {
        try {
            Dossier dossier = dossierRepository.findById(dossierId)
                    .orElseThrow(() -> new RuntimeException("Dossier introuvable"));

            Client client = dossier.getClient();
            if (client == null || client.getEmail() == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Email client introuvable"));
            }

            String eligibility = payload.getOrDefault("eligibility", "").toString();
            String explication = payload.getOrDefault("rawExplanation", "").toString();
            Object scoreObj    = payload.get("eligibilityScore");
            int    score       = scoreObj != null ? Integer.parseInt(scoreObj.toString()) : 0;
            String creditType  = payload.getOrDefault("creditType", "CONSOMMATION").toString();
            String prenom      = client.getPrenom() != null ? client.getPrenom() : "";
            String nom         = client.getNom()    != null ? client.getNom()    : "";

            // ✅ Couleur et icône selon décision
            String couleur = switch (eligibility) {
                case "ELIGIBLE"     -> "#16a34a";
                case "REFUS"        -> "#dc2626";
                case "CONDITIONNEL" -> "#d97706";
                default             -> "#6b7280";
            };

            String icone = switch (eligibility) {
                case "ELIGIBLE"     -> "✅";
                case "REFUS"        -> "❌";
                case "CONDITIONNEL" -> "⚠️";
                default             -> "ℹ️";
            };

            String messageDecision = switch (eligibility) {
                case "ELIGIBLE"     -> "Félicitations ! Votre dossier remplit tous les critères d'éligibilité au crédit consommation.";
                case "REFUS"        -> "Après analyse approfondie, votre dossier ne remplit pas actuellement les critères d'éligibilité. Nous vous invitons à contacter votre conseiller.";
                case "CONDITIONNEL" -> "Votre dossier est accepté sous conditions. Des garanties supplémentaires peuvent être requises. Votre conseiller vous contactera prochainement.";
                default             -> "Votre dossier est en cours d'analyse. Vous serez informé prochainement.";
            };

            String sujet = switch (eligibility) {
                case "ELIGIBLE"     -> "Votre demande de crédit a été approuvée — Attijariwafa Bank";
                case "REFUS"        -> "Résultat de votre demande de crédit — Attijariwafa Bank";
                case "CONDITIONNEL" -> "Décision conditionnelle sur votre demande — Attijariwafa Bank";
                default             -> "Résultat de votre demande de crédit — Attijariwafa Bank";
            };

            // ✅ Barre de score colorée
            String couleurScore = score >= 70 ? "#16a34a" : score >= 40 ? "#d97706" : "#dc2626";

            // ✅ HTML Email
            String html = String.format("""
            <!DOCTYPE html>
            <html lang="fr">
            <head>
              <meta charset="UTF-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            </head>
            <body style="margin:0;padding:0;background:#f4f4f4;font-family:Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#f4f4f4;padding:30px 0;">
                <tr><td align="center">
                  <table width="600" cellpadding="0" cellspacing="0"
                         style="background:#ffffff;border-radius:12px;overflow:hidden;box-shadow:0 4px 20px rgba(0,0,0,0.08);">

                    <!-- HEADER -->
                    <tr>
                      <td style="background:linear-gradient(135deg,#cc3300,#E8611A);padding:32px 40px;text-align:center;">
                        <h1 style="color:#ffffff;margin:0;font-size:24px;font-weight:700;letter-spacing:-0.5px;">
                          Attijariwafa Bank
                        </h1>
                        <p style="color:rgba(255,255,255,0.85);margin:6px 0 0;font-size:13px;">
                          CrediSense — Plateforme d'Analyse de Crédit
                        </p>
                      </td>
                    </tr>

                    <!-- SALUTATION -->
                    <tr>
                      <td style="padding:32px 40px 0;">
                        <p style="color:#1a1a2e;font-size:15px;margin:0;">
                          Bonjour <strong>%s %s</strong>,
                        </p>
                        <p style="color:#555;font-size:14px;margin:12px 0 0;line-height:1.6;">
                          Suite à l'analyse de votre dossier de crédit <strong>%s</strong>,
                          nous vous communiquons le résultat de notre évaluation.
                        </p>
                      </td>
                    </tr>

                    <!-- DÉCISION -->
                    <tr>
                      <td style="padding:24px 40px;">
                        <div style="background:%s15;border:2px solid %s;border-radius:10px;padding:24px;text-align:center;">
                          <div style="font-size:36px;margin-bottom:8px;">%s</div>
                          <div style="font-size:22px;font-weight:700;color:%s;margin-bottom:4px;">%s</div>
                          <div style="font-size:13px;color:#6b7280;">Décision sur votre demande de crédit</div>
                        </div>
                      </td>
                    </tr>

                    <!-- SCORE -->
                    <tr>
                      <td style="padding:0 40px 24px;">
                        <div style="background:#f8f9fc;border-radius:10px;padding:20px;">
                          <div style="display:flex;justify-content:space-between;margin-bottom:10px;">
                            <span style="font-size:13px;color:#6b7280;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;">
                              Score de crédit
                            </span>
                            <span style="font-size:18px;font-weight:700;color:%s;">
                              %d / 100
                            </span>
                          </div>
                          <div style="background:#e5e7eb;border-radius:99px;height:8px;overflow:hidden;">
                            <div style="background:%s;height:8px;width:%d%%;border-radius:99px;"></div>
                          </div>
                        </div>
                      </td>
                    </tr>

                    <!-- MESSAGE DÉCISION -->
                    <tr>
                      <td style="padding:0 40px 24px;">
                        <div style="background:#fff8f3;border-left:4px solid #E8611A;border-radius:0 8px 8px 0;padding:16px 20px;">
                          <p style="margin:0;font-size:14px;color:#444;line-height:1.7;">
                            %s
                          </p>
                        </div>
                      </td>
                    </tr>

                    <!-- ANALYSE DÉTAILLÉE -->
                    <tr>
                      <td style="padding:0 40px 24px;">
                        <h3 style="color:#1a1a2e;font-size:14px;font-weight:700;margin:0 0 12px;
                                   text-transform:uppercase;letter-spacing:0.5px;">
                          Analyse détaillée
                        </h3>
                        <p style="color:#555;font-size:13px;line-height:1.8;margin:0;
                                  background:#f8f9fc;border-radius:8px;padding:16px;">
                          %s
                        </p>
                      </td>
                    </tr>

                    <!-- CONTACT -->
                    <tr>
                      <td style="padding:0 40px 32px;">
                        <div style="border-top:1px solid #e5e7eb;padding-top:20px;">
                          <p style="color:#6b7280;font-size:13px;margin:0;line-height:1.6;">
                            Pour toute question concernant votre dossier, veuillez contacter
                            votre conseiller en agence ou appeler le <strong>71 141 400</strong>.
                          </p>
                        </div>
                      </td>
                    </tr>

                    <!-- FOOTER -->
                    <tr>
                      <td style="background:#1a1a2e;padding:24px 40px;text-align:center;">
                        <p style="color:rgba(255,255,255,0.6);font-size:12px;margin:0;">
                          © 2026 Attijariwafa Bank Tunisie — CrediSense
                        </p>
                        <p style="color:rgba(255,255,255,0.4);font-size:11px;margin:6px 0 0;">
                          Cet email est confidentiel et destiné uniquement à son destinataire.
                        </p>
                      </td>
                    </tr>

                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """,
                    prenom, nom,
                    creditType,
                    couleur, couleur,
                    icone,
                    couleur, eligibility,
                    couleurScore, score,
                    couleurScore, score,
                    messageDecision,
                    explication
            );

            // ✅ Envoi HTML avec MimeMessage
            jakarta.mail.internet.MimeMessage mimeMessage = mailSender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper =
                    new org.springframework.mail.javamail.MimeMessageHelper(
                            mimeMessage, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(client.getEmail());
            helper.setSubject(sujet);
            helper.setText(html, true); // ✅ true = HTML

            mailSender.send(mimeMessage);
            log.info("Email HTML envoyé à {} pour dossier {}", client.getEmail(), dossierId);

            return ResponseEntity.ok(Map.of(
                    "message", "Email envoyé avec succès à " + client.getEmail(),
                    "success", true
            ));

        } catch (Exception e) {
            log.error("Erreur envoi email résultat: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage(), "success", false));
        }
    }
}