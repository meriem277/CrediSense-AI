package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Envoie au client l'e-mail de réponse à sa demande de crédit (décision, explication), avec le
 * rapport PDF « version client » en pièce jointe.
 *
 * Utilisé à deux endroits : automatiquement dès que la décision est prise
 * (NotificationDecisionService) et à la demande de l'agent (bouton « Envoyer la réponse au client »).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultatEmailService {

    private final JavaMailSender   mailSender;
    private final RapportPdfService rapportPdfService;

    @Value("${spring.mail.username}")
    private String fromEmail;

    /** Ce qui a été envoyé, pour l'afficher à l'agent et le garder en base. */
    public record Envoi(String destinataire, String decision, boolean pdfJoint) {}

    /**
     * Envoie l'e-mail de décision. `payload` est le résultat de l'analyse (le JSON du service IA).
     * Lève une exception si l'envoi est impossible (pas d'adresse, serveur de messagerie en panne).
     */
    public Envoi envoyer(Dossier dossier, Map<String, Object> payload) throws Exception {
        Client client = dossier.getClient();
        if (client == null || client.getEmail() == null || client.getEmail().isBlank()) {
            throw new IllegalStateException("Email client introuvable");
        }

            String eligibility = payload.getOrDefault("eligibility", "").toString();
            String explication = echapper(payload.getOrDefault("rawExplanation", "").toString());
            Object scoreObj    = payload.get("eligibilityScore");
            int    score       = scoreObj != null ? Integer.parseInt(scoreObj.toString()) : 0;
            String creditType  = payload.getOrDefault("creditType", "CONSOMMATION").toString();
            String prenom      = echapper(client.getPrenom() != null ? client.getPrenom() : "");
            String nom         = echapper(client.getNom()    != null ? client.getNom()    : "");

            // ✅ Couleur et icône selon décision
            String couleur = switch (eligibility) {
                case "ELIGIBLE"     -> "#16a34a";
                case "REFUS"        -> "#dc2626";
                case "CONDITIONNEL" -> "#d97706";
                case "A_COMPLETER"  -> "#d97706";
                default             -> "#6b7280";
            };

            String icone = switch (eligibility) {
                case "ELIGIBLE"     -> "✅";
                case "REFUS"        -> "❌";
                case "CONDITIONNEL" -> "⚠️";
                case "A_COMPLETER"  -> "📄";
                default             -> "ℹ️";
            };

            String messageDecision = switch (eligibility) {
                case "ELIGIBLE"     -> "Félicitations ! Votre dossier remplit tous les critères d'éligibilité au crédit consommation.";
                case "REFUS"        -> "Après analyse approfondie, votre dossier ne remplit pas actuellement les critères d'éligibilité. Nous vous invitons à contacter votre conseiller.";
                case "CONDITIONNEL" -> "Votre dossier est accepté sous conditions. Des garanties supplémentaires peuvent être requises. Votre conseiller vous contactera prochainement.";
                case "A_COMPLETER"  -> "Votre dossier est incomplet : des informations complémentaires sont nécessaires pour pouvoir rendre une décision. Votre conseiller vous contactera pour les recueillir.";
                default             -> "Votre dossier est en cours d'analyse. Vous serez informé prochainement.";
            };

            String sujet = switch (eligibility) {
                case "ELIGIBLE"     -> "Votre demande de crédit a été approuvée — Attijari Bank";
                case "REFUS"        -> "Résultat de votre demande de crédit — Attijari Bank";
                case "CONDITIONNEL" -> "Décision conditionnelle sur votre demande — Attijari Bank";
                case "A_COMPLETER"  -> "Informations complémentaires nécessaires pour votre demande — Attijari Bank";
                default             -> "Résultat de votre demande de crédit — Attijari Bank";
            };

            // Libellé affiché dans l'e-mail (le code interne « A_COMPLETER » n'a pas à être montré)
            String libelleDecision = "A_COMPLETER".equals(eligibility) ? "DOSSIER À COMPLÉTER" : eligibility;

            // ✅ Barre de score colorée — pas de score pour un dossier à compléter : il serait
            // provisoire, et un chiffre provisoire envoyé au client est pris pour un verdict
            String couleurScore = score >= 70 ? "#16a34a" : score >= 40 ? "#d97706" : "#dc2626";
            String blocScore = "A_COMPLETER".equals(eligibility) ? "" : String.format("""
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
                    """, couleurScore, score, couleurScore, score);

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
                          Attijari Bank
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

                    <!-- SCORE (absent pour un dossier à compléter) -->
                    %s

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
                          © 2026 Attijari Bank Tunisie — CrediSense
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
                    couleur, libelleDecision,
                    blocScore,
                    messageDecision,
                    explication
            );

        // Rapport PDF « version client » en pièce jointe. S'il ne peut pas être généré, l'e-mail
        // part quand même : la décision compte plus que la pièce jointe.
        byte[] pdf = null;
        try {
            pdf = rapportPdfService.generer(dossier, payload, RapportPdfService.Version.CLIENT);
        } catch (Exception e) {
            log.warn("Rapport PDF non joint à l'e-mail du dossier {} : {}", dossier.getId(), e.getMessage());
        }

        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

        helper.setFrom(fromEmail);
        helper.setTo(client.getEmail());
        helper.setSubject(sujet);
        helper.setText(html, true); // true = HTML
        if (pdf != null) {
            helper.addAttachment(nomPiece(dossier), new ByteArrayResource(pdf), "application/pdf");
        }

        mailSender.send(mimeMessage);
        log.info("Email de décision {} envoyé à {} pour le dossier {} (PDF joint : {})",
                eligibility, client.getEmail(), dossier.getId(), pdf != null);

        return new Envoi(client.getEmail(), eligibility, pdf != null);
    }

    /** Nom du fichier joint : « Reponse-credit-1A2B3C4D.pdf ». */
    static String nomPiece(Dossier dossier) {
        String reference = dossier.getId() != null
                ? dossier.getId().toString().substring(0, 8).toUpperCase() : "dossier";
        return "Reponse-credit-" + reference + ".pdf";
    }

    /** Le texte venu de la base ou de l'IA ne doit pas pouvoir injecter de HTML dans l'e-mail. */
    static String echapper(String texte) {
        return texte == null ? "" : texte.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
