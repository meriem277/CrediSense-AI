package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.DecisionFinaleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Contrôle humain avant l'envoi : un refus attend « Valider et envoyer », les autres décisions
 * peuvent partir après un délai annulable, et chaque étape est inscrite au journal.
 */
class NotificationValidationTest {

    private ResultatEmailService        emailService;
    private AuditService                audit;
    private DecisionFinaleRepository    repository;
    private NotificationDecisionService service;
    private Dossier                     dossier;
    private DecisionFinale              df;

    @BeforeEach
    void preparer() throws Exception {
        emailService = mock(ResultatEmailService.class);
        repository   = mock(DecisionFinaleRepository.class);
        audit        = mock(AuditService.class);
        when(audit.acteurCourant()).thenReturn("agent@attijari.com");

        service = new NotificationDecisionService(emailService, repository, audit);
        ReflectionTestUtils.setField(service, "autoEnvoi", true);
        ReflectionTestUtils.setField(service, "validationRequisePour", "REFUS");
        ReflectionTestUtils.setField(service, "delaiMinutes", 0);

        Client client = new Client();
        client.setEmail("client@example.com");
        dossier = Dossier.builder().id(UUID.randomUUID()).client(client).build();
        df      = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier).build();

        when(emailService.envoyer(any(), any())).thenAnswer(i -> {
            Map<String, Object> r = i.getArgument(1);
            String decision = r == null ? "ELIGIBLE" : String.valueOf(r.get("eligibility"));
            return new ResultatEmailService.Envoi("client@example.com", decision, true);
        });
    }

    private Map<String, Object> resultat(String decision) {
        Map<String, Object> r = new HashMap<>();
        r.put("eligibility", decision);
        return r;
    }

    private void verifierJournal(String type, String decision, int fois) {
        verify(audit, times(fois)).enregistrer(eq(dossier.getId()), eq(type), eq(decision), any(), any(), any());
    }

    // ── Un refus attend la validation d'un agent ─────────────────────────────

    @Test
    void un_refus_n_est_pas_envoye_il_attend_la_validation() throws Exception {
        Map<String, Object> etat = service.notifier(dossier, df, resultat("REFUS"));

        verify(emailService, never()).envoyer(any(), any());
        assertEquals("EN_ATTENTE_VALIDATION", etat.get("statut"));
        assertEquals("EN_ATTENTE_VALIDATION", df.getEmailStatut());
        assertEquals("REFUS", df.getEmailDecision());
        assertEquals("client@example.com", df.getEmailDestinataire());
        assertNull(df.getEmailEnvoyeAt());
        assertTrue(String.valueOf(etat.get("detail")).contains("attend votre validation"));
        verify(repository).save(df);
        verifierJournal("EMAIL_EN_ATTENTE", "REFUS", 1);
    }

    @Test
    void les_autres_decisions_partent_sans_validation() throws Exception {
        for (String decision : new String[]{"ELIGIBLE", "CONDITIONNEL", "A_COMPLETER"}) {
            DecisionFinale autre = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier).build();
            assertEquals("ENVOYE", service.notifier(dossier, autre, resultat(decision)).get("statut"), decision);
            assertEquals("AUTO", autre.getEmailMode());
        }
        verify(emailService, times(3)).envoyer(any(), any());
    }

    @Test
    void les_decisions_qui_exigent_une_validation_se_configurent() throws Exception {
        ReflectionTestUtils.setField(service, "validationRequisePour", "refus ; conditionnel,a_completer");

        for (String decision : new String[]{"REFUS", "CONDITIONNEL", "A_COMPLETER"}) {
            DecisionFinale autre = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier).build();
            assertEquals("EN_ATTENTE_VALIDATION", service.notifier(dossier, autre, resultat(decision)).get("statut"));
        }
        DecisionFinale eligible = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier).build();
        assertEquals("ENVOYE", service.notifier(dossier, eligible, resultat("ELIGIBLE")).get("statut"));
    }

    @Test
    void sans_liste_aucune_decision_n_exige_de_validation() throws Exception {
        for (String vide : new String[]{"", "   ", null}) {
            ReflectionTestUtils.setField(service, "validationRequisePour", vide);
            DecisionFinale autre = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier).build();

            assertEquals("ENVOYE", service.notifier(dossier, autre, resultat("REFUS")).get("statut"));
        }
    }

    @Test
    void relancer_l_analyse_ne_remet_pas_en_attente_une_reponse_deja_en_attente() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));
        service.notifier(dossier, df, resultat("REFUS"));

        verifierJournal("EMAIL_EN_ATTENTE", "REFUS", 1);            // une seule mise en attente
        assertEquals("EN_ATTENTE_VALIDATION", df.getEmailStatut());
    }

    // ── Valider et envoyer ───────────────────────────────────────────────────

    @Test
    void valider_envoie_la_reponse_et_inscrit_la_validation_puis_l_envoi_au_journal() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));

        Map<String, Object> etat = service.valider(dossier, df, resultat("REFUS"), "REFUS");

        verify(emailService, times(1)).envoyer(eq(dossier), any());
        assertEquals("ENVOYE", etat.get("statut"));
        assertEquals("VALIDATION", df.getEmailMode());
        assertEquals("agent@attijari.com", df.getEmailActeur());
        assertNotNull(df.getEmailEnvoyeAt());
        verifierJournal("EMAIL_VALIDE", "REFUS", 1);
        verifierJournal("EMAIL_ENVOYE", "REFUS", 1);
    }

    @Test
    void valider_une_decision_qui_a_change_depuis_la_lecture_est_refuse() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));

        // l'agent a lu « REFUS » ; une nouvelle analyse a rendu « ELIGIBLE » entre-temps
        NotificationDecisionService.ReponseNonModifiableException erreur = assertThrows(
                NotificationDecisionService.ReponseNonModifiableException.class,
                () -> service.valider(dossier, df, resultat("ELIGIBLE"), "REFUS"));

        assertTrue(erreur.getMessage().contains("La décision a changé"));
        assertTrue(erreur.getMessage().contains("ELIGIBLE"));
        verify(emailService, never()).envoyer(any(), any());
        assertEquals("EN_ATTENTE_VALIDATION", df.getEmailStatut());      // rien n'a bougé
    }

    @Test
    void valider_sans_dire_quelle_decision_a_ete_lue_est_refuse() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));

        assertThrows(NotificationDecisionService.ReponseNonModifiableException.class,
                () -> service.valider(dossier, df, resultat("REFUS"), null));
        verify(emailService, never()).envoyer(any(), any());
    }

    @Test
    void valider_quand_rien_n_est_en_attente_est_refuse() throws Exception {
        NotificationDecisionService.ReponseNonModifiableException erreur = assertThrows(
                NotificationDecisionService.ReponseNonModifiableException.class,
                () -> service.valider(dossier, df, resultat("REFUS"), "REFUS"));

        assertTrue(erreur.getMessage().contains("Aucune réponse en attente"));
    }

    @Test
    void valider_ne_renvoie_pas_une_reponse_deja_partie() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));
        service.valider(dossier, df, resultat("REFUS"), "REFUS");

        assertThrows(NotificationDecisionService.ReponseNonModifiableException.class,
                () -> service.valider(dossier, df, resultat("REFUS"), "REFUS"));
        verify(emailService, times(1)).envoyer(any(), any());              // un seul e-mail au client
    }

    @Test
    void messagerie_en_panne_a_la_validation_l_echec_est_inscrit_sans_exception() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));
        when(emailService.envoyer(any(), any())).thenThrow(new RuntimeException("SMTP indisponible"));

        Map<String, Object> etat = assertDoesNotThrow(() -> service.valider(dossier, df, resultat("REFUS"), "REFUS"));

        assertEquals("ECHEC", etat.get("statut"));
        assertEquals("SMTP indisponible", df.getEmailErreur());
        verifierJournal("EMAIL_ECHEC", "REFUS", 1);
    }

    // ── Ne pas envoyer ───────────────────────────────────────────────────────

    @Test
    void annuler_ne_laisse_rien_partir_et_le_journal_le_dit() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));

        Map<String, Object> etat = service.annuler(dossier, df);

        verify(emailService, never()).envoyer(any(), any());
        assertEquals("ANNULE", etat.get("statut"));
        assertEquals("agent@attijari.com", df.getEmailActeur());
        assertTrue(String.valueOf(etat.get("detail")).contains("le client n'a rien reçu"));
        verifierJournal("EMAIL_ANNULE", "REFUS", 1);
    }

    @Test
    void une_reponse_annulee_ne_revient_pas_a_la_prochaine_analyse() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));
        service.annuler(dossier, df);

        Map<String, Object> etat = service.notifier(dossier, df, resultat("REFUS"));

        assertEquals("ANNULE", etat.get("statut"));
        verify(emailService, never()).envoyer(any(), any());
        verifierJournal("EMAIL_EN_ATTENTE", "REFUS", 1);                   // pas remise en attente
    }

    @Test
    void une_decision_qui_change_apres_une_annulation_ouvre_un_nouveau_cycle() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));
        service.annuler(dossier, df);

        Map<String, Object> etat = service.notifier(dossier, df, resultat("ELIGIBLE"));

        assertEquals("ENVOYE", etat.get("statut"));
        assertEquals("ELIGIBLE", df.getEmailDecision());
    }

    @Test
    void annuler_quand_rien_n_est_en_attente_est_refuse() {
        assertThrows(NotificationDecisionService.ReponseNonModifiableException.class,
                () -> service.annuler(dossier, df));
    }

    // ── Envoi différé annulable ──────────────────────────────────────────────

    @Test
    void avec_un_delai_la_reponse_est_programmee_et_pas_envoyee() throws Exception {
        ReflectionTestUtils.setField(service, "delaiMinutes", 10);
        LocalDateTime avant = LocalDateTime.now();

        Map<String, Object> etat = service.notifier(dossier, df, resultat("ELIGIBLE"));

        verify(emailService, never()).envoyer(any(), any());
        assertEquals("PROGRAMME", etat.get("statut"));
        assertEquals("DELAI", df.getEmailMode());
        assertTrue(df.getEmailProgrammeA().isAfter(avant.plusMinutes(9)));
        assertTrue(df.getEmailProgrammeA().isBefore(avant.plusMinutes(11)));
        assertTrue(String.valueOf(etat.get("detail")).contains("Envoi prévu à"));
        verifierJournal("EMAIL_EN_ATTENTE", "ELIGIBLE", 1);
    }

    @Test
    void le_refus_attend_une_validation_meme_avec_un_delai() throws Exception {
        ReflectionTestUtils.setField(service, "delaiMinutes", 10);

        assertEquals("EN_ATTENTE_VALIDATION", service.notifier(dossier, df, resultat("REFUS")).get("statut"));
    }

    @Test
    void l_echeance_atteinte_la_tache_automatique_envoie_la_reponse() throws Exception {
        df.setEmailStatut("PROGRAMME");
        df.setEmailDecision("ELIGIBLE");
        df.setEmailProgrammeA(LocalDateTime.now().minusMinutes(1));
        df.setResultatComplet("{\"eligibility\":\"ELIGIBLE\"}");
        when(repository.findProgrammesAEnvoyer(eq("PROGRAMME"), any())).thenReturn(List.of(df));
        when(audit.acteurCourant()).thenReturn("SYSTEME");

        int traites = service.envoyerProgrammes();

        assertEquals(1, traites);
        verify(emailService).envoyer(eq(dossier), any());
        assertEquals("ENVOYE", df.getEmailStatut());
        assertEquals("DELAI", df.getEmailMode());
        assertEquals("SYSTEME", df.getEmailActeur());
        assertNull(df.getEmailProgrammeA());
    }

    @Test
    void rien_a_envoyer_la_tache_ne_fait_rien() throws Exception {
        when(repository.findProgrammesAEnvoyer(any(), any())).thenReturn(List.of());

        assertEquals(0, service.envoyerProgrammes());
        verify(emailService, never()).envoyer(any(), any());
    }

    @Test
    void un_resultat_illisible_ou_une_panne_n_arretent_pas_les_autres_envois() throws Exception {
        DecisionFinale illisible = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier)
                .emailStatut("PROGRAMME").resultatComplet("{pas du json").build();
        DecisionFinale vide = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier)
                .emailStatut("PROGRAMME").build();
        DecisionFinale bon = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier)
                .emailStatut("PROGRAMME").emailDecision("ELIGIBLE").resultatComplet("{\"eligibility\":\"ELIGIBLE\"}").build();
        when(repository.findProgrammesAEnvoyer(any(), any())).thenReturn(List.of(illisible, vide, bon));

        assertDoesNotThrow(() -> service.envoyerProgrammes());

        assertEquals("ENVOYE", bon.getEmailStatut());                      // le bon est parti malgré les autres
        assertEquals("PROGRAMME", illisible.getEmailStatut());
    }

    @Test
    void envoyer_maintenant_une_reponse_programmee() throws Exception {
        ReflectionTestUtils.setField(service, "delaiMinutes", 10);
        service.notifier(dossier, df, resultat("ELIGIBLE"));

        Map<String, Object> etat = service.valider(dossier, df, resultat("ELIGIBLE"), "ELIGIBLE");

        assertEquals("ENVOYE", etat.get("statut"));
        assertEquals("VALIDATION", df.getEmailMode());
        assertNull(df.getEmailProgrammeA());
    }

    @Test
    void annuler_une_reponse_programmee_la_retire_de_la_file() throws Exception {
        ReflectionTestUtils.setField(service, "delaiMinutes", 10);
        service.notifier(dossier, df, resultat("ELIGIBLE"));

        Map<String, Object> etat = service.annuler(dossier, df);

        assertEquals("ANNULE", etat.get("statut"));
        assertNull(df.getEmailProgrammeA());
    }

    // ── Bouton « Envoyer la réponse au client » ──────────────────────────────

    @Test
    void l_envoi_manuel_marque_la_reponse_envoyee_et_l_inscrit_au_journal() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));        // en attente

        ResultatEmailService.Envoi envoi = service.envoyerManuellement(dossier, df, resultat("REFUS"));

        assertEquals("client@example.com", envoi.destinataire());
        assertEquals("ENVOYE", df.getEmailStatut());
        assertEquals("MANUEL", df.getEmailMode());
        verifierJournal("EMAIL_ENVOYE", "REFUS", 1);
    }

    @Test
    void l_envoi_manuel_sans_resultat_enregistre_envoie_quand_meme() throws Exception {
        assertDoesNotThrow(() -> service.envoyerManuellement(dossier, null, resultat("ELIGIBLE")));

        verify(emailService).envoyer(eq(dossier), any());
        verifierJournal("EMAIL_ENVOYE", "ELIGIBLE", 1);
    }

    @Test
    void l_echec_d_un_envoi_manuel_est_inscrit_puis_relance() throws Exception {
        when(emailService.envoyer(any(), any())).thenThrow(new IllegalStateException("Email client introuvable"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.envoyerManuellement(dossier, df, resultat("ELIGIBLE")));

        assertEquals("Email client introuvable", e.getMessage());
        verifierJournal("EMAIL_ECHEC", "ELIGIBLE", 1);
    }

    // ── Désactivation ────────────────────────────────────────────────────────

    @Test
    void envoi_automatique_desactive_meme_un_refus_n_est_pas_mis_en_attente() throws Exception {
        ReflectionTestUtils.setField(service, "autoEnvoi", false);

        Map<String, Object> etat = service.notifier(dossier, df, resultat("REFUS"));

        assertEquals("DESACTIVE", etat.get("statut"));
        assertNull(df.getEmailStatut());
        verify(audit, never()).enregistrer(any(), any(), any(), any(), any(), any());
    }

    // ── État affiché ─────────────────────────────────────────────────────────

    @Test
    void etat_d_une_reponse_programmee_donne_l_heure() {
        df.setEmailStatut("PROGRAMME");
        df.setEmailProgrammeA(LocalDateTime.of(2026, 10, 7, 14, 35));

        Map<String, Object> etat = service.etatDe(df);

        assertTrue(String.valueOf(etat.get("detail")).contains("14:35"));
        assertEquals("2026-10-07T14:35", etat.get("programmeA"));
    }

    @Test
    void etat_d_une_reponse_annulee_dit_par_qui() {
        df.setEmailStatut("ANNULE");
        df.setEmailActeur("agent@attijari.com");

        assertTrue(String.valueOf(service.etatDe(df).get("detail")).contains("annulé par agent@attijari.com"));
    }

    @Test
    void les_valeurs_nulles_sont_acceptees_par_le_journal() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));

        verify(audit).enregistrer(eq(dossier.getId()), eq("EMAIL_EN_ATTENTE"), eq("REFUS"), isNull(), isNull(), any());
    }
}
