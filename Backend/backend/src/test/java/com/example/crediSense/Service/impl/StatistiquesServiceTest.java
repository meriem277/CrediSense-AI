package com.example.crediSense.Service.impl;

import com.example.crediSense.Service.impl.StatistiquesService.LigneDecision;
import com.example.crediSense.Service.impl.StatistiquesService.Statistiques;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Les statistiques du tableau de bord : taux, délais, motifs, évolution, envois. Chaque définition
 * (« décision définitive », « taux d'acceptation »…) est verrouillée par un test.
 */
class StatistiquesServiceTest {

    private static final LocalDate DU = LocalDate.of(2026, 10, 1);
    private static final LocalDate AU = LocalDate.of(2026, 10, 10);

    private DecisionFinaleRepository decisions;
    private DossierRepository        dossiers;
    private StatistiquesService      service;
    private final List<DecisionFinale> lignes = new ArrayList<>();

    @BeforeEach
    void preparer() {
        decisions = mock(DecisionFinaleRepository.class);
        dossiers  = mock(DossierRepository.class);
        service   = new StatistiquesService(decisions, dossiers);
        when(decisions.findDecisionsEntre(any(), any())).thenAnswer(i -> lignes);
        when(dossiers.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(0L);
    }

    // ── Fabrique ─────────────────────────────────────────────────────────────

    private DecisionFinale decision(String decision, LocalDateTime depot, LocalDateTime decisionLe, String json) {
        Client client = new Client();
        client.setNom("Rehouma");
        client.setPrenom("Meriem");
        Dossier dossier = Dossier.builder().id(UUID.randomUUID()).createdAt(depot).montantCredit(1200.0)
                .dureeCredit(12).client(client).build();
        DecisionFinale df = DecisionFinale.builder().id(UUID.randomUUID()).decisionFinale(decision).scoreFinal(70.0)
                .decisionLe(decisionLe).resultatComplet(json).dossier(dossier).createdAt(decisionLe).build();
        lignes.add(df);
        return df;
    }

    private DecisionFinale decision(String decision) {
        return decision(decision, LocalDateTime.of(2026, 10, 3, 9, 0), LocalDateTime.of(2026, 10, 3, 11, 0), null);
    }

    private static final String REFUS_ENDETTEMENT = """
            {"eligibility":"REFUS","versionRegles":"2026-10-a","regulatoryChecks":[
              {"criterion":"Taux d'endettement","status":"KO"},
              {"criterion":"Plafond du montant","status":"KO"},
              {"criterion":"Type de contrat","status":"ATTENTION"},
              {"criterion":"Durée du crédit","status":"OK"}]}""";

    // ── Taux ─────────────────────────────────────────────────────────────────

    @Test
    void repartition_et_taux_sur_les_decisions_definitives() {
        for (int i = 0; i < 3; i++) decision("ELIGIBLE");
        decision("CONDITIONNEL");
        decision("REFUS");
        decision("REFUS");
        decision("A_COMPLETER");
        decision("A_COMPLETER");
        decision("INDETERMINE");

        Statistiques st = service.calculer(DU, AU);

        assertEquals(9, st.decisionsTotal());
        assertEquals(3, st.parDecision().get("ELIGIBLE"));
        assertEquals(1, st.parDecision().get("CONDITIONNEL"));
        assertEquals(2, st.parDecision().get("REFUS"));
        assertEquals(2, st.parDecision().get("A_COMPLETER"));
        assertEquals(1, st.parDecision().get("INDETERMINE"));
        // « à compléter » et « indéterminée » ne sont pas des décisions : 6 définitives sur 9
        assertEquals(6, st.decisionsDefinitives());
        assertEquals(3.0 / 6, st.tauxAcceptation(), 1e-9);
        assertEquals(4.0 / 6, st.tauxAcceptationAvecConditions(), 1e-9);
        assertEquals(2.0 / 6, st.tauxRefus(), 1e-9);
    }

    @Test
    void sans_decision_definitive_les_taux_sont_absents_et_non_zero() {
        decision("A_COMPLETER");

        Statistiques st = service.calculer(DU, AU);

        assertEquals(0, st.decisionsDefinitives());
        assertNull(st.tauxAcceptation());
        assertNull(st.tauxAcceptationAvecConditions());
        assertNull(st.tauxRefus());
    }

    @Test
    void aucune_donnee_tout_est_vide_sans_planter() {
        Statistiques st = service.calculer(DU, AU);

        assertEquals(0, st.decisionsTotal());
        assertNull(st.tauxAcceptation());
        assertEquals(0, st.delaiTraitement().echantillon());
        assertNull(st.delaiTraitement().moyenneHeures());
        assertNull(st.montantMoyenDemande());
        assertEquals(List.of(), st.motifs());
        assertEquals(10, st.evolution().size());                     // les jours existent, à zéro
        assertEquals(0, st.envois().envoyes());
    }

    @Test
    void une_decision_sans_valeur_compte_comme_indeterminee() {
        decision(null);

        assertEquals(1, service.calculer(DU, AU).parDecision().get("INDETERMINE"));
    }

    // ── Période ──────────────────────────────────────────────────────────────

    @Test
    void la_periode_va_du_debut_du_premier_jour_a_la_fin_du_dernier() {
        service.calculer(DU, AU);

        ArgumentCaptor<LocalDateTime> debut = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> fin = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(decisions).findDecisionsEntre(debut.capture(), fin.capture());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), debut.getValue());
        assertEquals(LocalDateTime.of(2026, 10, 11, 0, 0), fin.getValue());    // le 10 est inclus
        assertEquals(10, service.calculer(DU, AU).periode().jours());
    }

    @Test
    void les_dossiers_deposes_viennent_du_depot_et_pas_des_decisions() {
        when(dossiers.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(42L);
        decision("ELIGIBLE");

        Statistiques st = service.calculer(DU, AU);

        assertEquals(42, st.dossiersDeposes());
        assertEquals(1, st.decisionsTotal());
    }

    // ── Délais ───────────────────────────────────────────────────────────────

    @Test
    void delai_de_traitement_moyenne_mediane_et_maximum() {
        LocalDateTime depot = LocalDateTime.of(2026, 10, 3, 8, 0);
        decision("ELIGIBLE", depot, depot.plusHours(2), null);
        decision("ELIGIBLE", depot, depot.plusHours(4), null);
        decision("REFUS", depot, depot.plusHours(12), null);

        var d = service.calculer(DU, AU).delaiTraitement();

        assertEquals(3, d.echantillon());
        assertEquals(6.0, d.moyenneHeures(), 1e-9);
        assertEquals(4.0, d.medianeHeures(), 1e-9);
        assertEquals(12.0, d.maxHeures(), 1e-9);
    }

    @Test
    void mediane_d_un_nombre_pair_de_valeurs() {
        var d = StatistiquesService.delais(List.of(1.0, 2.0, 3.0, 10.0));

        assertEquals(2.5, d.medianeHeures(), 1e-9);
        assertEquals(4.0, d.moyenneHeures(), 1e-9);
    }

    @Test
    void un_delai_negatif_a_cause_de_dates_incoherentes_vaut_zero() {
        LocalDateTime depot = LocalDateTime.of(2026, 10, 3, 8, 0);
        decision("ELIGIBLE", depot, depot.minusHours(5), null);

        assertEquals(0.0, service.calculer(DU, AU).delaiTraitement().moyenneHeures(), 1e-9);
    }

    @Test
    void un_dossier_sans_date_de_depot_n_entre_pas_dans_les_delais() {
        decision("ELIGIBLE", null, LocalDateTime.of(2026, 10, 3, 8, 0), null);

        assertEquals(0, service.calculer(DU, AU).delaiTraitement().echantillon());
    }

    @Test
    void delai_d_envoi_de_la_decision_a_la_reponse_au_client() {
        LocalDateTime decisionLe = LocalDateTime.of(2026, 10, 3, 11, 0);
        DecisionFinale df = decision("REFUS", decisionLe.minusHours(1), decisionLe, null);
        df.setEmailEnvoyeAt(decisionLe.plusHours(3));        // validé 3 h après la décision
        decision("ELIGIBLE");                                  // jamais envoyée : ne compte pas

        var d = service.calculer(DU, AU).delaiEnvoi();

        assertEquals(1, d.echantillon());
        assertEquals(3.0, d.moyenneHeures(), 1e-9);
    }

    // ── Motifs ───────────────────────────────────────────────────────────────

    @Test
    void motifs_de_refus_criteres_reglementaires_en_echec() {
        decision("REFUS", LocalDateTime.of(2026, 10, 3, 9, 0), LocalDateTime.of(2026, 10, 3, 11, 0), REFUS_ENDETTEMENT);
        decision("REFUS", LocalDateTime.of(2026, 10, 3, 9, 0), LocalDateTime.of(2026, 10, 3, 11, 0),
                "{\"regulatoryChecks\":[{\"criterion\":\"Taux d'endettement\",\"status\":\"KO\"}]}");

        var motifs = service.calculer(DU, AU).motifs();

        assertEquals("Taux d'endettement", motifs.get(0).motif());      // le plus fréquent d'abord
        assertEquals(2, motifs.get(0).nombre());
        assertEquals("REFUS", motifs.get(0).decision());
        assertEquals("Plafond du montant", motifs.get(1).motif());
        assertEquals(1, motifs.get(1).nombre());
        assertEquals(2, motifs.size());                                   // ATTENTION et OK ne sont pas des motifs de refus
    }

    @Test
    void un_conditionnel_retient_aussi_les_criteres_en_attention() {
        decision("CONDITIONNEL", LocalDateTime.of(2026, 10, 3, 9, 0), LocalDateTime.of(2026, 10, 3, 11, 0), REFUS_ENDETTEMENT);

        List<String> motifs = StatistiquesService.motifsDe("CONDITIONNEL",
                new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                        java.util.Map.of("regulatoryChecks", List.of(
                                java.util.Map.of("criterion", "Type de contrat", "status", "ATTENTION"),
                                java.util.Map.of("criterion", "Durée du crédit", "status", "OK"))), java.util.Map.class));

        assertEquals(List.of("Type de contrat"), motifs);
    }

    @Test
    void un_refus_sans_critere_en_echec_est_range_dans_appreciation_de_l_analyse() {
        decision("REFUS", LocalDateTime.of(2026, 10, 3, 9, 0), LocalDateTime.of(2026, 10, 3, 11, 0),
                "{\"regulatoryChecks\":[{\"criterion\":\"Durée\",\"status\":\"OK\"}]}");

        assertEquals(StatistiquesService.MOTIF_AUTRE, service.calculer(DU, AU).motifs().get(0).motif());
    }

    @Test
    void un_dossier_a_completer_a_pour_motifs_les_informations_manquantes() {
        decision("A_COMPLETER", LocalDateTime.of(2026, 10, 3, 9, 0), LocalDateTime.of(2026, 10, 3, 11, 0),
                "{\"donneesManquantes\":[\"Dettes existantes — relevé bancaire des 3 derniers mois\","
                + "\"Date d'embauche — attestation de travail\"]}");

        var motifs = service.calculer(DU, AU).motifs();

        assertEquals(2, motifs.size());
        assertTrue(motifs.stream().anyMatch(m -> m.motif().equals("Information manquante : Dettes existantes")));
        assertTrue(motifs.stream().anyMatch(m -> m.motif().equals("Information manquante : Date d'embauche")));
    }

    @Test
    void un_dossier_eligible_n_a_pas_de_motif_et_un_resultat_illisible_ne_plante_pas() {
        decision("ELIGIBLE", LocalDateTime.of(2026, 10, 3, 9, 0), LocalDateTime.of(2026, 10, 3, 11, 0), REFUS_ENDETTEMENT);
        decision("REFUS", LocalDateTime.of(2026, 10, 3, 9, 0), LocalDateTime.of(2026, 10, 3, 11, 0), "{pas du json");
        decision("REFUS");

        assertEquals(List.of(), service.calculer(DU, AU).motifs());
    }

    @Test
    void motifs_absents_si_aucun_resultat() {
        assertEquals(List.of(), StatistiquesService.motifsDe("REFUS", null));
    }

    // ── Évolution ────────────────────────────────────────────────────────────

    @Test
    void evolution_par_jour_avec_les_jours_sans_decision_a_zero() {
        decision("ELIGIBLE", LocalDateTime.of(2026, 10, 2, 8, 0), LocalDateTime.of(2026, 10, 3, 9, 0), null);
        decision("REFUS",    LocalDateTime.of(2026, 10, 2, 8, 0), LocalDateTime.of(2026, 10, 3, 17, 0), null);
        decision("A_COMPLETER", LocalDateTime.of(2026, 10, 5, 8, 0), LocalDateTime.of(2026, 10, 5, 9, 0), null);

        Statistiques st = service.calculer(DU, AU);

        assertEquals("JOUR", st.granularite());
        assertEquals(10, st.evolution().size());
        assertEquals("2026-10-01", st.evolution().get(0).date());
        assertEquals(0, st.evolution().get(0).total());
        var troisieme = st.evolution().get(2);
        assertEquals("2026-10-03", troisieme.date());
        assertEquals(1, troisieme.eligible());
        assertEquals(1, troisieme.refus());
        assertEquals(2, troisieme.total());
        assertEquals(1, st.evolution().get(4).aCompleter());
    }

    @Test
    void la_date_de_decision_prime_sur_la_date_de_premiere_analyse() {
        DecisionFinale df = decision("ELIGIBLE", LocalDateTime.of(2026, 10, 1, 8, 0), LocalDateTime.of(2026, 10, 8, 9, 0), null);
        df.setCreatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));            // première analyse, il y a longtemps

        Statistiques st = service.calculer(DU, AU);

        assertEquals(1, st.evolution().get(7).eligible());               // le 8 octobre
        assertEquals(0, st.evolution().get(1).eligible());
    }

    @Test
    void sans_date_de_decision_on_retombe_sur_la_creation() {
        DecisionFinale df = decision("ELIGIBLE", LocalDateTime.of(2026, 10, 1, 8, 0), null, null);
        df.setCreatedAt(LocalDateTime.of(2026, 10, 4, 9, 0));

        assertEquals(1, service.calculer(DU, AU).evolution().get(3).eligible());
    }

    @Test
    void sur_une_longue_periode_l_evolution_est_regroupee_par_semaine() {
        LocalDate du = LocalDate.of(2026, 7, 1);       // mercredi
        LocalDate au = LocalDate.of(2026, 10, 10);     // 102 jours
        decision("ELIGIBLE", LocalDateTime.of(2026, 7, 8, 8, 0), LocalDateTime.of(2026, 7, 8, 9, 0), null);   // mercredi
        decision("REFUS",    LocalDateTime.of(2026, 7, 9, 8, 0), LocalDateTime.of(2026, 7, 10, 9, 0), null);  // vendredi, même semaine

        Statistiques st = service.calculer(du, au);

        assertEquals("SEMAINE", st.granularite());
        assertEquals("2026-06-29", st.evolution().get(0).date());                 // le lundi de la première semaine
        var semaine = st.evolution().get(1);                                       // semaine du 6 juillet
        assertEquals("2026-07-06", semaine.date());
        assertEquals(1, semaine.eligible());
        assertEquals(1, semaine.refus());
        assertTrue(st.evolution().stream().allMatch(p -> LocalDate.parse(p.date()).getDayOfWeek().getValue() == 1));
    }

    @Test
    void soixante_deux_jours_restent_par_jour_soixante_trois_passent_en_semaines() {
        assertEquals("JOUR", service.calculer(DU, DU.plusDays(61)).granularite());
        assertEquals("SEMAINE", service.calculer(DU, DU.plusDays(62)).granularite());
    }

    // ── Envois ───────────────────────────────────────────────────────────────

    @Test
    void etat_des_reponses_envoyees_aux_clients() {
        String[] statuts = {"ENVOYE", "ENVOYE", "ECHEC", "NON_ENVOYE", "EN_ATTENTE_VALIDATION", "PROGRAMME", "ANNULE", null};
        for (String statut : statuts) decision("REFUS").setEmailStatut(statut);
        when(decisions.countByEmailStatut("EN_ATTENTE_VALIDATION")).thenReturn(7L);

        var envois = service.calculer(DU, AU).envois();

        assertEquals(2, envois.envoyes());
        assertEquals(1, envois.echecs());
        assertEquals(1, envois.nonEnvoyes());
        assertEquals(1, envois.enAttenteValidation());
        assertEquals(1, envois.programmes());
        assertEquals(1, envois.annules());
        assertEquals(1, envois.sansEnvoi());
        assertEquals(7, envois.enAttenteValidationTotal());              // toutes périodes : ce qui attend un agent
    }

    // ── Montant ──────────────────────────────────────────────────────────────

    @Test
    void montant_moyen_demande() {
        decision("ELIGIBLE").getDossier().setMontantCredit(1000.0);
        decision("REFUS").getDossier().setMontantCredit(3000.0);
        decision("REFUS").getDossier().setMontantCredit(null);            // sans montant : ignoré

        assertEquals(2000.0, service.calculer(DU, AU).montantMoyenDemande(), 1e-9);
    }

    // ── Détail pour l'export ─────────────────────────────────────────────────

    @Test
    void les_lignes_de_detail_sont_les_plus_recentes_d_abord_avec_tout_ce_qu_il_faut() {
        LocalDateTime depot = LocalDateTime.of(2026, 10, 3, 8, 0);
        DecisionFinale ancienne = decision("ELIGIBLE", depot, depot.plusHours(1), null);
        DecisionFinale recente = decision("REFUS", depot, depot.plusHours(30), REFUS_ENDETTEMENT);
        recente.setEmailStatut("ENVOYE");
        recente.setEmailMode("VALIDATION");
        recente.setEmailEnvoyeAt(depot.plusHours(40));

        List<LigneDecision> detail = service.rapport(DU, AU).lignes();

        assertEquals(2, detail.size());
        LigneDecision l = detail.get(0);                                   // la plus récente
        assertEquals("REFUS", l.decision());
        assertEquals("Meriem Rehouma", l.client());
        assertEquals(recente.getDossier().getId().toString().substring(0, 8).toUpperCase(), l.reference());
        assertEquals(30.0, l.delaiHeures(), 1e-9);
        assertEquals("Taux d'endettement", l.motifPrincipal());
        assertEquals("2026-10-a", l.versionRegles());
        assertEquals("ENVOYE", l.statutEmail());
        assertEquals("VALIDATION", l.modeEnvoi());
        assertEquals(1200.0, l.montant());
        assertEquals(12, l.duree());
        assertEquals("ELIGIBLE", detail.get(1).decision());
        assertEquals("", detail.get(1).motifPrincipal());
        assertNotNull(ancienne);
    }

    @Test
    void le_rapport_ne_lit_la_base_qu_une_fois() {
        decision("ELIGIBLE");

        service.rapport(DU, AU);

        verify(decisions, times(1)).findDecisionsEntre(any(), any());
    }
}
