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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Statistiques du tableau de bord : taux d'acceptation, délais de traitement, motifs de refus,
 * évolution dans le temps et état des envois. Calculées à la demande à partir des décisions
 * enregistrées (rien n'est stocké en double).
 *
 * Définitions (affichées telles quelles à l'écran) :
 *  - décision DÉFINITIVE : éligible, conditionnel ou refus. « À compléter » n'est pas une décision :
 *    le dossier attend des pièces. « Indéterminée » est un échec technique de l'analyse ;
 *  - taux d'acceptation : éligibles / décisions définitives ;
 *  - taux d'acceptation avec conditions : (éligibles + conditionnels) / décisions définitives ;
 *  - délai de traitement : du dépôt du dossier à la décision ;
 *  - délai d'envoi : de la décision à l'envoi de la réponse au client (inclut l'attente de validation).
 *
 * Limite : chaque décision est relue et son résultat JSON analysé en mémoire. C'est instantané pour
 * quelques milliers de dossiers ; au-delà, il faudrait précalculer les motifs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatistiquesService {

    /** Au-delà de cette durée, l'évolution est regroupée par semaine au lieu de par jour. */
    static final int JOURS_MAX_PAR_JOUR = 62;
    /** Période maximale demandée (garde-fou : on ne relit pas toute la base pour rien). */
    public static final int JOURS_MAX = 800;

    static final String MOTIF_AUTRE = "Appréciation de l'analyse (aucun critère réglementaire en échec)";

    private final DecisionFinaleRepository decisionFinaleRepository;
    private final DossierRepository        dossierRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Résultat ─────────────────────────────────────────────────────────────

    public record Periode(LocalDate du, LocalDate au, int jours) {}

    /** Durées en heures ; null si aucune donnée. */
    public record Delais(Double moyenneHeures, Double medianeHeures, Double maxHeures, int echantillon) {}

    public record Motif(String decision, String motif, int nombre) {}

    public record PointEvolution(String date, int eligible, int conditionnel, int refus, int aCompleter, int total) {}

    public record Envois(int envoyes, int echecs, int nonEnvoyes, int enAttenteValidation, int programmes,
                         int annules, int sansEnvoi, long enAttenteValidationTotal) {}

    public record Statistiques(Periode periode, int dossiersDeposes, int decisionsTotal,
                               Map<String, Integer> parDecision, int decisionsDefinitives,
                               Double tauxAcceptation, Double tauxAcceptationAvecConditions, Double tauxRefus,
                               Delais delaiTraitement, Delais delaiEnvoi, Double montantMoyenDemande,
                               List<Motif> motifs, String granularite, List<PointEvolution> evolution,
                               Envois envois) {}

    /** Une ligne par décision, pour l'export Excel. */
    public record LigneDecision(String reference, String client, LocalDateTime depotLe, LocalDateTime decisionLe,
                                Double delaiHeures, String decision, Double score, Double montant, Integer duree,
                                String motifPrincipal, String statutEmail, LocalDateTime emailEnvoyeLe,
                                String modeEnvoi, String versionRegles) {}

    public record Rapport(Statistiques statistiques, List<LigneDecision> lignes) {}

    // ── Calcul ───────────────────────────────────────────────────────────────

    public Statistiques calculer(LocalDate du, LocalDate au) {
        return rapport(du, au).statistiques();
    }

    /** Statistiques et lignes de détail d'une même période, avec une seule lecture de la base. */
    public Rapport rapport(LocalDate du, LocalDate au) {
        LocalDateTime debut = du.atStartOfDay();
        LocalDateTime fin   = au.plusDays(1).atStartOfDay();

        List<DecisionFinale> decisions = decisionFinaleRepository.findDecisionsEntre(debut, fin);
        long deposes = dossierRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(debut, fin);

        Map<String, Integer> parDecision = new LinkedHashMap<>();
        for (String d : List.of("ELIGIBLE", "CONDITIONNEL", "REFUS", "A_COMPLETER", "INDETERMINE")) parDecision.put(d, 0);

        List<Double> delaisTraitement = new ArrayList<>();
        List<Double> delaisEnvoi      = new ArrayList<>();
        List<Double> montants         = new ArrayList<>();
        Map<String, Integer> motifs   = new HashMap<>();
        Map<String, int[]> evolution  = new TreeMap<>();
        int envoyes = 0, echecs = 0, nonEnvoyes = 0, enAttente = 0, programmes = 0, annules = 0, sansEnvoi = 0;
        List<LigneDecision> lignes = new ArrayList<>();

        boolean parSemaine = periode(du, au).jours() > JOURS_MAX_PAR_JOUR;

        for (DecisionFinale df : decisions) {
            Dossier dossier = df.getDossier();
            LocalDateTime dateDecision = df.getDecisionLe() != null ? df.getDecisionLe() : df.getCreatedAt();
            String decision = df.getDecisionFinale() != null ? df.getDecisionFinale() : "INDETERMINE";
            Map<String, Object> resultat = lireResultat(df);

            parDecision.merge(decision, 1, Integer::sum);

            // Délais
            Double delai = dossier != null ? heures(dossier.getCreatedAt(), dateDecision) : null;
            if (delai != null) delaisTraitement.add(delai);
            if (df.getEmailEnvoyeAt() != null) {
                Double delaiEnvoi = heures(dateDecision, df.getEmailEnvoyeAt());
                if (delaiEnvoi != null) delaisEnvoi.add(delaiEnvoi);
            }
            if (dossier != null && dossier.getMontantCredit() != null) montants.add(dossier.getMontantCredit());

            // Motifs
            List<String> motifsDuDossier = motifsDe(decision, resultat);
            for (String motif : motifsDuDossier) motifs.merge(decision + "\u0000" + motif, 1, Integer::sum);

            // Évolution
            LocalDate jour = dateDecision.toLocalDate();
            LocalDate cle  = parSemaine ? jour.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : jour;
            int[] compteurs = evolution.computeIfAbsent(cle.toString(), k -> new int[4]);
            switch (decision) {
                case "ELIGIBLE"     -> compteurs[0]++;
                case "CONDITIONNEL" -> compteurs[1]++;
                case "REFUS"        -> compteurs[2]++;
                case "A_COMPLETER"  -> compteurs[3]++;
                default             -> { }
            }

            // Envois
            String statut = df.getEmailStatut();
            if (statut == null)                                      sansEnvoi++;
            else switch (statut) {
                case NotificationDecisionService.ENVOYE                -> envoyes++;
                case NotificationDecisionService.ECHEC                 -> echecs++;
                case NotificationDecisionService.NON_ENVOYE            -> nonEnvoyes++;
                case NotificationDecisionService.EN_ATTENTE_VALIDATION -> enAttente++;
                case NotificationDecisionService.PROGRAMME             -> programmes++;
                case NotificationDecisionService.ANNULE                -> annules++;
                default                                                -> sansEnvoi++;
            }

            lignes.add(ligne(df, dossier, dateDecision, decision, delai, resultat, motifsDuDossier));
        }
        lignes.sort(Comparator.comparing(LigneDecision::decisionLe, Comparator.nullsLast(Comparator.reverseOrder())));

        int eligibles    = parDecision.get("ELIGIBLE");
        int conditionnels = parDecision.get("CONDITIONNEL");
        int refus        = parDecision.get("REFUS");
        int definitives  = eligibles + conditionnels + refus;

        Statistiques stats = new Statistiques(
                periode(du, au), (int) deposes, decisions.size(), parDecision, definitives,
                taux(eligibles, definitives), taux(eligibles + conditionnels, definitives), taux(refus, definitives),
                delais(delaisTraitement), delais(delaisEnvoi), moyenne(montants),
                trierMotifs(motifs), parSemaine ? "SEMAINE" : "JOUR", remplir(evolution, du, au, parSemaine),
                new Envois(envoyes, echecs, nonEnvoyes, enAttente, programmes, annules, sansEnvoi,
                        decisionFinaleRepository.countByEmailStatut(NotificationDecisionService.EN_ATTENTE_VALIDATION)));
        return new Rapport(stats, lignes);
    }

    // ── Aides ────────────────────────────────────────────────────────────────

    static Periode periode(LocalDate du, LocalDate au) {
        return new Periode(du, au, (int) ChronoUnit.DAYS.between(du, au) + 1);
    }

    private static Double taux(int partie, int total) {
        return total == 0 ? null : (double) partie / total;
    }

    private static Double heures(LocalDateTime de, LocalDateTime a) {
        if (de == null || a == null) return null;
        return Math.max(0.0, Duration.between(de, a).toSeconds() / 3600.0);
    }

    private static Double moyenne(List<Double> valeurs) {
        OptionalDouble m = valeurs.stream().mapToDouble(Double::doubleValue).average();
        return m.isPresent() ? m.getAsDouble() : null;
    }

    static Delais delais(List<Double> valeurs) {
        if (valeurs.isEmpty()) return new Delais(null, null, null, 0);
        List<Double> triees = valeurs.stream().sorted().toList();
        int n = triees.size();
        double mediane = n % 2 == 1 ? triees.get(n / 2) : (triees.get(n / 2 - 1) + triees.get(n / 2)) / 2.0;
        return new Delais(moyenne(valeurs), mediane, triees.get(n - 1), n);
    }

    /**
     * Pourquoi cette décision : critères réglementaires en échec pour un refus (ou « attention » pour un
     * conditionnel), informations manquantes pour un dossier à compléter.
     */
    @SuppressWarnings("unchecked")
    static List<String> motifsDe(String decision, Map<String, Object> resultat) {
        List<String> motifs = new ArrayList<>();
        if (resultat == null) return motifs;

        if ("A_COMPLETER".equals(decision)) {
            if (resultat.get("donneesManquantes") instanceof List<?> manquantes) {
                for (Object m : manquantes) {
                    String texte = String.valueOf(m);
                    // « Dettes existantes — relevé bancaire des 3 derniers mois » → « Dettes existantes »
                    String nom = texte.contains(" — ") ? texte.substring(0, texte.indexOf(" — ")) : texte;
                    motifs.add("Information manquante : " + nom.trim());
                }
            }
            return motifs;
        }
        if (!"REFUS".equals(decision) && !"CONDITIONNEL".equals(decision)) return motifs;

        if (resultat.get("regulatoryChecks") instanceof List<?> controles) {
            for (Object o : controles) {
                if (!(o instanceof Map<?, ?> c)) continue;
                String statut = String.valueOf(c.get("status"));
                boolean retenu = "KO".equals(statut) || ("CONDITIONNEL".equals(decision) && "ATTENTION".equals(statut));
                if (retenu && c.get("criterion") != null) motifs.add(c.get("criterion").toString());
            }
        }
        if (motifs.isEmpty()) motifs.add(MOTIF_AUTRE);
        return motifs;
    }

    private static List<Motif> trierMotifs(Map<String, Integer> comptes) {
        List<Motif> motifs = new ArrayList<>();
        comptes.forEach((cle, nombre) -> {
            String[] parties = cle.split("\u0000", 2);
            motifs.add(new Motif(parties[0], parties[1], nombre));
        });
        motifs.sort(Comparator.comparingInt(Motif::nombre).reversed()
                .thenComparing(Motif::decision).thenComparing(Motif::motif));
        return motifs;
    }

    /** Une valeur pour chaque jour (ou chaque semaine) de la période, même sans décision. */
    private static List<PointEvolution> remplir(Map<String, int[]> evolution, LocalDate du, LocalDate au,
                                                boolean parSemaine) {
        List<PointEvolution> points = new ArrayList<>();
        LocalDate courant = parSemaine ? du.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : du;
        while (!courant.isAfter(au)) {
            int[] c = evolution.getOrDefault(courant.toString(), new int[4]);
            points.add(new PointEvolution(courant.toString(), c[0], c[1], c[2], c[3], c[0] + c[1] + c[2] + c[3]));
            courant = courant.plusDays(parSemaine ? 7 : 1);
        }
        return points;
    }

    private LigneDecision ligne(DecisionFinale df, Dossier dossier, LocalDateTime dateDecision, String decision,
                                Double delai, Map<String, Object> resultat, List<String> motifs) {
        Client client = dossier != null ? dossier.getClient() : null;
        String nom = client == null ? "" : ((client.getPrenom() != null ? client.getPrenom() : "") + " "
                + (client.getNom() != null ? client.getNom() : "")).trim();
        String reference = dossier != null && dossier.getId() != null
                ? dossier.getId().toString().substring(0, 8).toUpperCase() : "";
        return new LigneDecision(reference, nom,
                dossier != null ? dossier.getCreatedAt() : null, dateDecision, delai, decision, df.getScoreFinal(),
                dossier != null ? dossier.getMontantCredit() : null, dossier != null ? dossier.getDureeCredit() : null,
                motifs.isEmpty() ? "" : motifs.get(0), df.getEmailStatut(), df.getEmailEnvoyeAt(), df.getEmailMode(),
                resultat != null && resultat.get("versionRegles") != null ? resultat.get("versionRegles").toString() : null);
    }

    private Map<String, Object> lireResultat(DecisionFinale df) {
        if (df.getResultatComplet() == null || df.getResultatComplet().isBlank()) return null;
        try {
            return objectMapper.readValue(df.getResultatComplet(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Statistiques : résultat illisible pour la décision {} : {}", df.getId(), e.getMessage());
            return null;
        }
    }
}
