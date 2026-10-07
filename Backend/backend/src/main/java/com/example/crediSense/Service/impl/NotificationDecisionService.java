package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Envoie la réponse au client dès que la décision est prise, avec un contrôle humain réglable.
 *
 * Trois comportements selon la décision :
 *  - décision qui EXIGE UNE VALIDATION (par défaut : le refus) : la réponse attend le bouton
 *    « Valider et envoyer » de l'agent, qui peut aussi choisir de ne pas l'envoyer ;
 *  - envoi DIFFÉRÉ (AUTO_ENVOI délai &gt; 0) : la réponse part après N minutes, sauf si l'agent
 *    l'annule ou la fait partir tout de suite ;
 *  - sinon, envoi IMMÉDIAT.
 *
 * Règles communes :
 *  - on n'écrit au client que pour une décision rendue (éligible, refus, conditionnel, « à compléter ») ;
 *    une analyse échouée techniquement n'envoie rien ;
 *  - une même décision n'est jamais traitée deux fois : relancer l'analyse ne renvoie pas d'e-mail,
 *    ne remet pas en attente une réponse déjà en attente, et ne ressuscite pas une réponse annulée ;
 *    si la décision CHANGE, un nouveau cycle commence ;
 *  - valider porte sur la décision que l'agent a lue : si elle a changé entre-temps, le serveur refuse ;
 *  - un échec d'envoi ne fait jamais échouer l'analyse ;
 *  - chaque étape est inscrite au journal d'audit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDecisionService {

    static final Set<String> DECISIONS_NOTIFIEES = Set.of("ELIGIBLE", "REFUS", "CONDITIONNEL", "A_COMPLETER");

    // États de la réponse
    public static final String ENVOYE                = "ENVOYE";
    public static final String ECHEC                 = "ECHEC";
    public static final String NON_ENVOYE            = "NON_ENVOYE";
    public static final String EN_ATTENTE_VALIDATION = "EN_ATTENTE_VALIDATION";
    public static final String PROGRAMME             = "PROGRAMME";
    public static final String ANNULE                = "ANNULE";
    public static final String DESACTIVE             = "DESACTIVE";
    public static final String AUCUN                 = "AUCUN";

    // États qu'une nouvelle analyse de la MÊME décision ne doit pas modifier
    private static final Set<String> STATUTS_STABLES = Set.of(ENVOYE, ANNULE, EN_ATTENTE_VALIDATION, PROGRAMME);

    // Modes d'envoi
    public static final String MODE_AUTO       = "AUTO";
    public static final String MODE_DELAI      = "DELAI";
    public static final String MODE_VALIDATION = "VALIDATION";
    public static final String MODE_MANUEL     = "MANUEL";

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");

    private final ResultatEmailService      emailService;
    private final DecisionFinaleRepository  decisionFinaleRepository;
    private final AuditService              auditService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AUTO_ENVOI_EMAIL=false : plus aucun envoi automatique, l'agent utilise le bouton manuel. */
    @Value("${app.notifications.auto-envoi:true}")
    private boolean autoEnvoi;

    /** Décisions qui attendent la validation d'un agent (liste séparée par des virgules ; vide : aucune). */
    @Value("${app.notifications.validation-requise-pour:REFUS}")
    private String validationRequisePour;

    /** Délai (en minutes) avant l'envoi automatique d'une réponse sans validation requise ; 0 : immédiat. */
    @Value("${app.notifications.delai-minutes:0}")
    private int delaiMinutes;

    /** La demande de l'agent ne peut pas être exécutée dans l'état actuel (rien en attente, décision changée). */
    public static class ReponseNonModifiableException extends RuntimeException {
        public ReponseNonModifiableException(String message) { super(message); }
    }

    // ═════════════════════════════ Décision rendue ═════════════════════════════

    /**
     * Traite la réponse d'une décision qui vient d'être enregistrée. Ne lève jamais d'exception.
     * Renvoie l'état de la réponse, tel qu'il sera affiché à l'agent.
     */
    public Map<String, Object> notifier(Dossier dossier, DecisionFinale df, Map<String, Object> resultat) {
        try {
            String decision = String.valueOf(resultat.getOrDefault("eligibility", ""));

            if (!autoEnvoi) {
                return etat(DESACTIVE, "L'envoi automatique est désactivé : utilisez « Envoyer la réponse au client ».",
                        decision);
            }
            if (!DECISIONS_NOTIFIEES.contains(decision)) {
                return etat(AUCUN, "Décision non définitive : aucun e-mail envoyé au client.", decision);
            }
            // Même décision déjà traitée (envoyée, annulée, ou déjà en attente) : on ne la retraite pas
            if (decision.equals(df.getEmailDecision()) && STATUTS_STABLES.contains(df.getEmailStatut())) {
                log.info("Décision {} déjà traitée (état {}) pour le dossier {} : rien à faire",
                        decision, df.getEmailStatut(), dossier.getId());
                return etatDe(df);
            }

            if (exigeValidation(decision)) {
                mettreEnAttente(dossier, df, decision, EN_ATTENTE_VALIDATION, null,
                        "une réponse " + decision + " doit être validée par un agent avant l'envoi");
                return etatDe(df);
            }
            if (delaiMinutes > 0) {
                LocalDateTime prevu = LocalDateTime.now().plusMinutes(delaiMinutes);
                mettreEnAttente(dossier, df, decision, PROGRAMME, prevu,
                        "envoi programmé à " + prevu.format(HEURE) + ", annulable d'ici là");
                return etatDe(df);
            }
            return envoyer(dossier, df, resultat, decision, MODE_AUTO);

        } catch (Exception e) {
            log.error("Notification de la décision du dossier {} en échec : {}", dossier.getId(), e.getMessage());
            return etat(ECHEC, "Notification impossible : " + e.getMessage(), null);
        }
    }

    // ═════════════════════════ Actions de l'agent sur la réponse ═════════════════════════

    /**
     * « Valider et envoyer » (ou « Envoyer maintenant » pour un envoi différé).
     * `decisionVue` est la décision que l'agent a lue à l'écran : si une nouvelle analyse l'a changée
     * entre-temps, on refuse plutôt que d'envoyer au client autre chose que ce qui a été relu.
     */
    public Map<String, Object> valider(Dossier dossier, DecisionFinale df, Map<String, Object> resultat,
                                       String decisionVue) {
        if (!EN_ATTENTE_VALIDATION.equals(df.getEmailStatut()) && !PROGRAMME.equals(df.getEmailStatut())) {
            throw new ReponseNonModifiableException("Aucune réponse en attente de validation pour ce dossier.");
        }
        String decisionActuelle = String.valueOf(resultat.getOrDefault("eligibility", ""));
        if (decisionVue == null || !decisionVue.equals(decisionActuelle)
                || !decisionActuelle.equals(df.getEmailDecision())) {
            throw new ReponseNonModifiableException("La décision a changé depuis votre lecture (elle est maintenant « "
                    + decisionActuelle + " »). Relisez le dossier avant de valider.");
        }
        auditService.enregistrer(dossier.getId(), AuditService.EMAIL_VALIDE, decisionActuelle, null, null,
                detail("decision", decisionActuelle));
        return envoyer(dossier, df, resultat, decisionActuelle, MODE_VALIDATION);
    }

    /** « Ne pas envoyer » / « Annuler l'envoi » : le client ne recevra rien pour cette décision. */
    public Map<String, Object> annuler(Dossier dossier, DecisionFinale df) {
        if (!EN_ATTENTE_VALIDATION.equals(df.getEmailStatut()) && !PROGRAMME.equals(df.getEmailStatut())) {
            throw new ReponseNonModifiableException("Rien à annuler : la réponse n'est pas en attente.");
        }
        df.setEmailStatut(ANNULE);
        df.setEmailProgrammeA(null);
        df.setEmailActeur(auditService.acteurCourant());
        df.setEmailErreur(null);
        decisionFinaleRepository.save(df);
        auditService.enregistrer(dossier.getId(), AuditService.EMAIL_ANNULE, df.getEmailDecision(), null, null,
                detail("decision", df.getEmailDecision()));
        return etatDe(df);
    }

    /**
     * Bouton « Envoyer la réponse au client » : envoi immédiat à la demande de l'agent, quelle que soit
     * la configuration. `df` peut être null (aucun résultat enregistré) : l'e-mail part, rien n'est suivi.
     * L'erreur d'envoi est inscrite au journal puis relancée à l'appelant.
     */
    public ResultatEmailService.Envoi envoyerManuellement(Dossier dossier, DecisionFinale df,
                                                          Map<String, Object> resultat) throws Exception {
        String decision = String.valueOf(resultat.getOrDefault("eligibility", ""));
        try {
            ResultatEmailService.Envoi envoi = emailService.envoyer(dossier, resultat);
            if (df != null) {
                marquerEnvoye(df, envoi, MODE_MANUEL);
                decisionFinaleRepository.save(df);
            }
            auditService.enregistrer(dossier.getId(), AuditService.EMAIL_ENVOYE, decision, null, null,
                    detailEnvoi(envoi, MODE_MANUEL));
            return envoi;
        } catch (Exception e) {
            auditService.enregistrer(dossier.getId(), AuditService.EMAIL_ECHEC, decision, null, null,
                    detail("erreur", e.getMessage(), "mode", MODE_MANUEL));
            throw e;
        }
    }

    // ═════════════════════════ Envoi différé : tâche automatique ═════════════════════════

    /** Envoie les réponses dont le délai est écoulé et que l'agent n'a ni annulées ni envoyées. */
    @Scheduled(fixedDelayString = "${app.notifications.scan-ms:30000}")
    public int envoyerProgrammes() {
        List<DecisionFinale> dus = decisionFinaleRepository.findProgrammesAEnvoyer(PROGRAMME, LocalDateTime.now());
        for (DecisionFinale df : dus) {
            try {
                Map<String, Object> resultat = lireResultat(df);
                if (resultat == null) continue;
                envoyer(df.getDossier(), df, resultat, String.valueOf(resultat.get("eligibility")), MODE_DELAI);
            } catch (Exception e) {
                log.error("Envoi différé impossible pour la décision {} : {}", df.getId(), e.getMessage());
            }
        }
        return dus.size();
    }

    // ═════════════════════════════ Mécanique interne ═════════════════════════════

    private boolean exigeValidation(String decision) {
        if (validationRequisePour == null || validationRequisePour.isBlank()) return false;
        Set<String> exigees = Arrays.stream(validationRequisePour.split("[,; ]+"))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return exigees.contains(decision);
    }

    private void mettreEnAttente(Dossier dossier, DecisionFinale df, String decision, String statut,
                                 LocalDateTime programmeA, String raison) {
        df.setEmailStatut(statut);
        df.setEmailDecision(decision);
        df.setEmailProgrammeA(programmeA);
        df.setEmailMode(EN_ATTENTE_VALIDATION.equals(statut) ? MODE_VALIDATION : MODE_DELAI);
        df.setEmailEnvoyeAt(null);
        df.setEmailErreur(null);
        df.setEmailActeur(null);
        df.setEmailDestinataire(dossier.getClient() != null ? dossier.getClient().getEmail() : null);
        decisionFinaleRepository.save(df);
        auditService.enregistrer(dossier.getId(), AuditService.EMAIL_EN_ATTENTE, decision, null, null,
                detail("raison", raison, "statut", statut));
    }

    private Map<String, Object> envoyer(Dossier dossier, DecisionFinale df, Map<String, Object> resultat,
                                        String decision, String mode) {
        try {
            ResultatEmailService.Envoi envoi = emailService.envoyer(dossier, resultat);
            marquerEnvoye(df, envoi, mode);
            auditService.enregistrer(dossier.getId(), AuditService.EMAIL_ENVOYE, decision, null, null,
                    detailEnvoi(envoi, mode));
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            boolean sansAdresse = e instanceof IllegalStateException;
            df.setEmailStatut(sansAdresse ? NON_ENVOYE : ECHEC);
            df.setEmailDecision(decision);
            df.setEmailErreur(tronquer(message));
            df.setEmailEnvoyeAt(null);
            df.setEmailDestinataire(null);
            df.setEmailProgrammeA(null);
            df.setEmailMode(mode);
            // ECHEC et NON_ENVOYE ne sont pas des états « stables » : la prochaine analyse retentera l'envoi
            log.warn("Envoi de la réponse impossible pour le dossier {} : {}", dossier.getId(), message);
            auditService.enregistrer(dossier.getId(), AuditService.EMAIL_ECHEC, decision, null, null,
                    detail("erreur", message, "mode", mode));
        }
        decisionFinaleRepository.save(df);
        return etatDe(df);
    }

    /** Enregistre qu'un e-mail de décision est parti, et par quel chemin. */
    public void marquerEnvoye(DecisionFinale df, ResultatEmailService.Envoi envoi, String mode) {
        df.setEmailStatut(ENVOYE);
        df.setEmailEnvoyeAt(LocalDateTime.now());
        df.setEmailDecision(envoi.decision());
        df.setEmailDestinataire(envoi.destinataire());
        df.setEmailErreur(null);
        df.setEmailProgrammeA(null);
        df.setEmailMode(mode);
        df.setEmailActeur(auditService.acteurCourant());
    }

    /** Compatibilité : envoi déclenché par l'agent. */
    public void marquerEnvoye(DecisionFinale df, ResultatEmailService.Envoi envoi) {
        marquerEnvoye(df, envoi, MODE_MANUEL);
    }

    // ═════════════════════════════ État affiché à l'agent ═════════════════════════════

    /** L'état de la réponse d'une décision déjà enregistrée (null si rien n'a été tenté). */
    public Map<String, Object> etatDe(DecisionFinale df) {
        if (df == null || df.getEmailStatut() == null) return null;

        String detail = switch (df.getEmailStatut()) {
            case ENVOYE -> "Réponse envoyée au client.";
            case ECHEC -> "L'envoi a échoué : " + df.getEmailErreur();
            case EN_ATTENTE_VALIDATION -> "Cette réponse attend votre validation avant d'être envoyée au client.";
            case PROGRAMME -> "Envoi prévu à "
                    + (df.getEmailProgrammeA() != null ? df.getEmailProgrammeA().format(HEURE) : "l'heure prévue")
                    + ". Vous pouvez l'envoyer maintenant ou l'annuler.";
            case ANNULE -> "Envoi annulé par " + df.getEmailActeur() + " : le client n'a rien reçu.";
            default -> "Aucun e-mail envoyé : " + df.getEmailErreur();
        };

        Map<String, Object> etat = etat(df.getEmailStatut(), detail, df.getEmailDecision());
        etat.put("destinataire", df.getEmailDestinataire());
        etat.put("envoyeAt", df.getEmailEnvoyeAt() != null ? df.getEmailEnvoyeAt().toString() : null);
        etat.put("programmeA", df.getEmailProgrammeA() != null ? df.getEmailProgrammeA().toString() : null);
        etat.put("mode", df.getEmailMode());
        etat.put("acteur", df.getEmailActeur());
        return etat;
    }

    private static Map<String, Object> etat(String statut, String detail, String decision) {
        Map<String, Object> etat = new HashMap<>();
        etat.put("statut", statut);
        etat.put("detail", detail);
        etat.put("destinataire", null);
        etat.put("envoyeAt", null);
        etat.put("decision", decision);
        return etat;
    }

    private Map<String, Object> lireResultat(DecisionFinale df) {
        if (df.getResultatComplet() == null || df.getResultatComplet().isBlank()) return null;
        try {
            return objectMapper.readValue(df.getResultatComplet(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Résultat enregistré illisible pour la décision {} : {}", df.getId(), e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> detailEnvoi(ResultatEmailService.Envoi envoi, String mode) {
        return detail("destinataire", envoi.destinataire(), "mode", mode, "pdfJoint", envoi.pdfJoint());
    }

    /** Petite carte clé / valeur ; les valeurs nulles sont ignorées. */
    private static Map<String, Object> detail(Object... clesValeurs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < clesValeurs.length; i += 2) {
            if (clesValeurs[i + 1] != null) m.put(String.valueOf(clesValeurs[i]), clesValeurs[i + 1]);
        }
        return m;
    }

    private static String tronquer(String message) {
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
