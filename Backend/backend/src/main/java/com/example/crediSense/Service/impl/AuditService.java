package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.AuditEvent;
import com.example.crediSense.repository.AuditEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Journal d'audit des dossiers : qui a décidé quoi, quand, avec quelle version des règles, et quel
 * e-mail est parti. En ajout seul : ce service écrit et lit, jamais ne modifie ni ne supprime.
 *
 * Écrire dans le journal ne doit jamais faire échouer l'opération qu'il décrit : une erreur est
 * consignée dans les journaux du serveur et l'opération continue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String ANALYSE                = "ANALYSE";
    public static final String INCOHERENCE_CONFIRMEE  = "INCOHERENCE_CONFIRMEE";
    public static final String EMAIL_EN_ATTENTE       = "EMAIL_EN_ATTENTE";
    public static final String EMAIL_VALIDE           = "EMAIL_VALIDE";
    public static final String EMAIL_ANNULE           = "EMAIL_ANNULE";
    public static final String EMAIL_ENVOYE           = "EMAIL_ENVOYE";
    public static final String EMAIL_ECHEC            = "EMAIL_ECHEC";
    public static final String RAPPORT_TELECHARGE     = "RAPPORT_TELECHARGE";
    public static final String STATUT_MODIFIE         = "STATUT_MODIFIE";

    public static final String SYSTEME = "SYSTEME";

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** L'agent connecté qui effectue l'action, ou « SYSTEME » (tâche automatique, requête sans jeton). */
    public String acteurCourant() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken
                || a.getName() == null || a.getName().isBlank()) {
            return SYSTEME;
        }
        return a.getName();
    }

    public void enregistrer(UUID dossierId, String type, String decision, Double score,
                            String versionRegles, Map<String, Object> detail) {
        try {
            repository.save(AuditEvent.builder()
                    .dossierId(dossierId)
                    .type(type)
                    .acteur(acteurCourant())
                    .decision(decision)
                    .score(score)
                    .versionRegles(versionRegles)
                    .detail(detail == null || detail.isEmpty() ? null : objectMapper.writeValueAsString(detail))
                    .build());
        } catch (Exception e) {
            log.error("Journal d'audit : événement {} non enregistré pour le dossier {} : {}",
                    type, dossierId, e.getMessage());
        }
    }

    /** Le journal d'un dossier, chronologique, prêt à afficher. */
    public List<Map<String, Object>> journal(UUID dossierId) {
        List<Map<String, Object>> lignes = new ArrayList<>();
        for (AuditEvent e : repository.findByDossierIdOrderByCreatedAtAsc(dossierId)) {
            Map<String, Object> detail = lireDetail(e.getDetail());

            Map<String, Object> ligne = new LinkedHashMap<>();
            ligne.put("id", String.valueOf(e.getId()));
            ligne.put("date", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            ligne.put("type", e.getType());
            ligne.put("acteur", e.getActeur());
            ligne.put("decision", e.getDecision());
            ligne.put("score", e.getScore());
            ligne.put("versionRegles", e.getVersionRegles());
            ligne.put("detail", detail);
            ligne.put("libelle", libelle(e.getType(), e.getActeur(), e.getDecision(), e.getScore(), detail));
            lignes.add(ligne);
        }
        return lignes;
    }

    private Map<String, Object> lireDetail(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** Phrase lisible décrivant l'événement (c'est elle que l'agent lit dans la frise). */
    static String libelle(String type, String acteur, String decision, Double score, Map<String, Object> d) {
        String par = SYSTEME.equals(acteur) ? "" : " par " + acteur;
        return switch (type == null ? "" : type) {
            case ANALYSE -> "Analyse terminée : décision " + libelleDecision(decision)
                    + (score != null && !"A_COMPLETER".equals(decision) ? ", score " + Math.round(score) + "/100" : "")
                    + (d.get("provider") != null ? " (IA : " + d.get("provider") + ")" : "");
            case INCOHERENCE_CONFIRMEE -> "Incohérence confirmée manuellement" + par
                    + (d.get("details") != null ? " : " + d.get("details") : "");
            case EMAIL_EN_ATTENTE -> "Réponse " + libelleDecision(decision) + " mise en attente : " + raison(d);
            case EMAIL_VALIDE -> "Envoi de la réponse validé" + par;
            case EMAIL_ANNULE -> "Envoi de la réponse annulé" + par + " : le client n'a rien reçu";
            case EMAIL_ENVOYE -> "Réponse " + libelleDecision(decision) + " envoyée à " + d.getOrDefault("destinataire", "?")
                    + " (" + libelleMode(String.valueOf(d.get("mode"))) + ")"
                    + (Boolean.TRUE.equals(d.get("pdfJoint")) ? ", rapport PDF joint" : "");
            case EMAIL_ECHEC -> "Échec de l'envoi de la réponse : " + d.getOrDefault("erreur", "erreur inconnue");
            case RAPPORT_TELECHARGE -> "Rapport PDF « " + d.getOrDefault("version", "agent") + " » téléchargé" + par;
            case STATUT_MODIFIE -> "Statut du dossier modifié" + par + " : " + d.getOrDefault("ancien", "?")
                    + " → " + d.getOrDefault("nouveau", "?");
            default -> type == null ? "" : type;
        };
    }

    private static String raison(Map<String, Object> d) {
        Object r = d.get("raison");
        return r != null ? r.toString() : "en attente";
    }

    private static String libelleMode(String mode) {
        return switch (mode) {
            case "AUTO"       -> "envoi automatique";
            case "DELAI"      -> "après le délai prévu";
            case "VALIDATION" -> "après validation";
            case "MANUEL"     -> "à la demande de l'agent";
            default           -> mode;
        };
    }

    static String libelleDecision(String decision) {
        return switch (decision == null ? "" : decision) {
            case "ELIGIBLE"     -> "ÉLIGIBLE";
            case "REFUS"        -> "REFUS";
            case "CONDITIONNEL" -> "CONDITIONNEL";
            case "A_COMPLETER"  -> "À COMPLÉTER";
            case "INDETERMINE"  -> "INDÉTERMINÉE";
            default             -> decision == null ? "?" : decision;
        };
    }
}
