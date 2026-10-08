package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Propositions d'ajustement faites au client pour un dossier CONDITIONNEL, et sa réponse (accepter l'une
 * d'elles, ou les refuser toutes).
 *
 * Règles :
 *  - les propositions ne deviennent visibles du client qu'une fois <b>la réponse envoyée</b> (statut
 *    d'envoi « ENVOYE ») : un agent qui a mis la réponse en validation garde la main ;
 *  - elles n'existent que pour une décision CONDITIONNEL dont le moteur a calculé au moins une offre ;
 *  - le client ne répond qu'une fois ; l'offre qu'il a choisie est conservée telle qu'il l'a vue ;
 *  - une nouvelle analyse qui change la décision ou les propositions efface sa réponse (elle ne
 *    correspondrait plus à ce qu'il a accepté) ; le journal le consigne.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropositionClientService {

    public static final String EN_ATTENTE_REPONSE = "EN_ATTENTE_REPONSE";
    public static final String ACCEPTEE           = "ACCEPTEE";
    public static final String REFUSEE            = "REFUSEE";

    /** Champs d'une offre montrés au client (liste blanche : rien d'interne ne passe). */
    private static final List<String> CHAMPS_OFFRE =
            List.of("kind", "label", "amount", "duration", "monthlyPayment", "dti", "totalCost", "explanation");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DossierRepository dossierRepository;
    private final DecisionFinaleRepository decisionFinaleRepository;
    private final AuditService auditService;

    /** Erreur métier avec le code HTTP à renvoyer : 400 choix invalide, 404 rien à proposer, 409 déjà répondu. */
    public static class PropositionException extends RuntimeException {
        private final int statut;
        public PropositionException(int statut, String message) { super(message); this.statut = statut; }
        public int getStatut() { return statut; }
    }

    // ── Lecture ───────────────────────────────────────────────────────────────

    /** Ce que le client voit pour un dossier, ou vide si rien ne lui est proposé (ou pas encore). */
    public Optional<Map<String, Object>> propositionPour(DecisionFinale df) {
        List<Map<String, Object>> offres = offresDisponibles(df);
        if (offres.isEmpty()) return Optional.empty();

        Map<String, Object> vue = new LinkedHashMap<>();
        vue.put("decision", df.getDecisionFinale());
        vue.put("message", messageDe(df));
        vue.put("offres", offres);
        vue.put("etat", etatDe(df));
        vue.put("choix", df.getOffreChoisieIndex());
        vue.put("repondueLe", df.getReponseClientLe() != null ? df.getReponseClientLe().toString() : null);
        return Optional.of(vue);
    }

    /** Résumé par dossier du client : {dossierId, etat} pour ceux qui ont une proposition. */
    public List<Map<String, Object>> propositionsDuClient(Client client) {
        List<Map<String, Object>> resultat = new ArrayList<>();
        for (Dossier d : dossierRepository.findByClient(client)) {
            decisionFinaleRepository.findByDossierId(d.getId())
                    .filter(df -> !offresDisponibles(df).isEmpty())
                    .ifPresent(df -> {
                        Map<String, Object> ligne = new LinkedHashMap<>();
                        ligne.put("dossierId", d.getId().toString());
                        ligne.put("etat", etatDe(df));
                        resultat.add(ligne);
                    });
        }
        return resultat;
    }

    /** Ce que voit l'agent : la réponse du client, ou null s'il n'y a rien à signaler. */
    public static Map<String, Object> etatPourAgent(DecisionFinale df) {
        if (df == null || df.getReponseClient() == null) return null;
        Map<String, Object> etat = new LinkedHashMap<>();
        etat.put("statut", df.getReponseClient());
        etat.put("repondueLe", df.getReponseClientLe() != null ? df.getReponseClientLe().toString() : null);
        etat.put("offre", lireOffre(df.getOffreChoisieJson()));
        return etat;
    }

    // ── Réponse du client ─────────────────────────────────────────────────────

    /**
     * Enregistre la réponse du client : « ACCEPTER » une offre (numéro à partir de 0) ou « REFUSER » toutes.
     * Lève {@link PropositionException} si rien n'est proposé (404), si le choix est invalide (400) ou si le
     * client a déjà répondu (409).
     */
    public Map<String, Object> repondre(Dossier dossier, DecisionFinale df, String choix, Integer numeroOffre) {
        List<Map<String, Object>> offres = offresDisponibles(df);
        if (offres.isEmpty()) {
            throw new PropositionException(404, "Aucune proposition n'est disponible pour cette demande.");
        }
        if (df.getReponseClient() != null) {
            throw new PropositionException(409, "Vous avez déjà répondu à cette proposition.");
        }

        String action = choix == null ? "" : choix.trim().toUpperCase();
        Map<String, Object> detail = new LinkedHashMap<>();

        switch (action) {
            case "ACCEPTER" -> {
                if (numeroOffre == null || numeroOffre < 0 || numeroOffre >= offres.size()) {
                    throw new PropositionException(400, "Choisissez l'une des propositions proposées.");
                }
                Map<String, Object> offre = offres.get(numeroOffre);
                df.setReponseClient(ACCEPTEE);
                df.setOffreChoisieIndex(numeroOffre);
                df.setOffreChoisieJson(ecrire(offre));
                detail.put("label", offre.get("label"));
                detail.put("montant", offre.get("amount"));
                detail.put("duree", offre.get("duration"));
            }
            case "REFUSER" -> {
                df.setReponseClient(REFUSEE);
                df.setOffreChoisieIndex(null);
                df.setOffreChoisieJson(null);
            }
            default -> throw new PropositionException(400, "Réponse inconnue : indiquez ACCEPTER ou REFUSER.");
        }
        df.setReponseClientLe(LocalDateTime.now());
        decisionFinaleRepository.save(df);

        auditService.enregistrer(dossier.getId(),
                ACCEPTEE.equals(df.getReponseClient()) ? AuditService.OFFRE_ACCEPTEE : AuditService.OFFRE_REFUSEE,
                df.getDecisionFinale(), df.getScoreFinal(), versionRegles(df), detail);
        log.info("Réponse du client au dossier {} : {}", dossier.getId(), df.getReponseClient());

        return propositionPour(df).orElseThrow();
    }

    // ── Nouvelle analyse : la réponse précédente a-t-elle encore un sens ? ────

    /**
     * À appeler quand une nouvelle analyse remplace le résultat d'un dossier. Efface la réponse du client si
     * la décision n'est plus CONDITIONNEL ou si l'offre qu'il avait choisie n'est plus proposée telle quelle.
     * Retourne vrai si elle a été effacée (à consigner dans le journal).
     */
    public static boolean reinitialiserSiObsolete(DecisionFinale df, Map<String, Object> nouveauResultat) {
        if (df == null || df.getReponseClient() == null) return false;

        boolean toujoursValable = false;
        if ("CONDITIONNEL".equals(String.valueOf(nouveauResultat.get("eligibility")))) {
            if (REFUSEE.equals(df.getReponseClient())) {
                toujoursValable = !offresDe(nouveauResultat).isEmpty();
            } else {
                Map<String, Object> choisie = lireOffre(df.getOffreChoisieJson());
                toujoursValable = choisie != null && offresDe(nouveauResultat).stream().anyMatch(o -> memeOffre(o, choisie));
            }
        }
        if (toujoursValable) return false;

        df.setReponseClient(null);
        df.setOffreChoisieIndex(null);
        df.setOffreChoisieJson(null);
        df.setReponseClientLe(null);
        return true;
    }

    // ── Analyse du résultat enregistré ────────────────────────────────────────

    private List<Map<String, Object>> offresDisponibles(DecisionFinale df) {
        if (df == null || !"CONDITIONNEL".equals(df.getDecisionFinale())) return List.of();
        // Rien n'est visible tant que la réponse n'est pas partie chez le client (validation par un agent)
        if (!"ENVOYE".equals(df.getEmailStatut())) return List.of();
        return offresDe(lireResultat(df.getResultatComplet()));
    }

    /** Offres du résultat de l'analyse, réduites aux champs destinés au client. */
    static List<Map<String, Object>> offresDe(Map<String, Object> resultat) {
        if (resultat == null || !(resultat.get("adjustedOffers") instanceof Map<?, ?> propositions)) return List.of();
        if (!Boolean.TRUE.equals(propositions.get("applicable"))) return List.of();
        if (!(propositions.get("offers") instanceof List<?> liste)) return List.of();

        List<Map<String, Object>> offres = new ArrayList<>();
        for (Object o : liste) {
            if (!(o instanceof Map<?, ?> brute)) continue;
            Map<String, Object> offre = new LinkedHashMap<>();
            for (String champ : CHAMPS_OFFRE) {
                if (brute.containsKey(champ)) offre.put(champ, brute.get(champ));
            }
            if (offre.containsKey("amount") && offre.containsKey("duration")) offres.add(offre);
        }
        return offres;
    }

    private static boolean memeOffre(Map<String, Object> a, Map<String, Object> b) {
        return nombre(a.get("amount")) == nombre(b.get("amount"))
                && nombre(a.get("duration")) == nombre(b.get("duration"))
                && String.valueOf(a.get("kind")).equals(String.valueOf(b.get("kind")));
    }

    private static double nombre(Object o) {
        try { return o == null ? Double.NaN : Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    private static String etatDe(DecisionFinale df) {
        if (df.getReponseClient() == null) return EN_ATTENTE_REPONSE;
        return df.getReponseClient();
    }

    private String messageDe(DecisionFinale df) {
        Map<String, Object> resultat = lireResultat(df.getResultatComplet());
        if (resultat != null && resultat.get("adjustedOffers") instanceof Map<?, ?> p && p.get("message") != null) {
            return p.get("message").toString();
        }
        return "";
    }

    private static String versionRegles(DecisionFinale df) {
        Map<String, Object> resultat = lireResultat(df.getResultatComplet());
        return resultat != null && resultat.get("versionRegles") != null ? resultat.get("versionRegles").toString() : null;
    }

    private static Map<String, Object> lireResultat(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> lireOffre(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static String ecrire(Map<String, Object> offre) {
        try {
            return MAPPER.writeValueAsString(offre);
        } catch (Exception e) {
            throw new IllegalStateException("Offre illisible : " + e.getMessage(), e);
        }
    }
}
