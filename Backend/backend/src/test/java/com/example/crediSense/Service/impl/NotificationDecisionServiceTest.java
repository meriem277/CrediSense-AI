package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.DecisionFinaleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Envoi automatique de la réponse au client dès la décision : une fois par décision, jamais pour
 * une analyse ratée, et sans jamais faire échouer l'analyse.
 */
class NotificationDecisionServiceTest {

    private ResultatEmailService       emailService;
    private AuditService               audit;
    private DecisionFinaleRepository   repository;
    private NotificationDecisionService service;
    private Dossier                    dossier;
    private DecisionFinale             df;

    @BeforeEach
    void preparer() throws Exception {
        emailService = mock(ResultatEmailService.class);
        repository   = mock(DecisionFinaleRepository.class);
        audit        = mock(AuditService.class);
        when(audit.acteurCourant()).thenReturn("agent@attijari.com");
        service      = new NotificationDecisionService(emailService, repository, audit);
        ReflectionTestUtils.setField(service, "autoEnvoi", true);
        // par défaut : aucune validation requise et envoi immédiat (les tests de validation le changent)
        ReflectionTestUtils.setField(service, "validationRequisePour", "");
        ReflectionTestUtils.setField(service, "delaiMinutes", 0);

        dossier = Dossier.builder().id(UUID.randomUUID()).build();
        df      = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier).build();

        when(emailService.envoyer(any(), any())).thenAnswer(i -> {
            // Mockito rappelle cette réponse avec des arguments vides quand un test la redéfinit
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

    // ── Envoi automatique ────────────────────────────────────────────────────

    @Test
    void une_decision_rendue_envoie_la_reponse_et_l_enregistre() throws Exception {
        Map<String, Object> etat = service.notifier(dossier, df, resultat("ELIGIBLE"));

        verify(emailService, times(1)).envoyer(eq(dossier), any());
        assertEquals("ENVOYE", df.getEmailStatut());
        assertEquals("ELIGIBLE", df.getEmailDecision());
        assertEquals("client@example.com", df.getEmailDestinataire());
        assertNotNull(df.getEmailEnvoyeAt());
        assertNull(df.getEmailErreur());
        verify(repository).save(df);
        assertEquals("ENVOYE", etat.get("statut"));
        assertEquals("client@example.com", etat.get("destinataire"));
        assertNotNull(etat.get("envoyeAt"));
    }

    @Test
    void chaque_decision_rendue_est_notifiee() throws Exception {
        for (String decision : new String[]{"ELIGIBLE", "REFUS", "CONDITIONNEL", "A_COMPLETER"}) {
            DecisionFinale autre = DecisionFinale.builder().build();
            assertEquals("ENVOYE", service.notifier(dossier, autre, resultat(decision)).get("statut"), decision);
        }
        verify(emailService, times(4)).envoyer(any(), any());
    }

    // ── Pas de doublon ───────────────────────────────────────────────────────

    @Test
    void relancer_l_analyse_sans_changement_de_decision_ne_renvoie_pas_d_e_mail() throws Exception {
        service.notifier(dossier, df, resultat("REFUS"));
        Map<String, Object> seconde = service.notifier(dossier, df, resultat("REFUS"));

        verify(emailService, times(1)).envoyer(any(), any());      // un seul e-mail au client
        assertEquals("ENVOYE", seconde.get("statut"));              // l'agent voit toujours « envoyé »
        assertNotNull(seconde.get("envoyeAt"));
    }

    @Test
    void une_decision_qui_change_envoie_la_nouvelle_reponse() throws Exception {
        service.notifier(dossier, df, resultat("A_COMPLETER"));
        service.notifier(dossier, df, resultat("ELIGIBLE"));

        verify(emailService, times(2)).envoyer(any(), any());
        assertEquals("ELIGIBLE", df.getEmailDecision());
    }

    // ── Pas d'envoi ──────────────────────────────────────────────────────────

    @Test
    void une_analyse_ratee_n_ecrit_pas_au_client() throws Exception {
        Map<String, Object> etat = service.notifier(dossier, df, resultat("INDETERMINE"));

        verify(emailService, never()).envoyer(any(), any());
        verify(repository, never()).save(any());
        assertEquals("AUCUN", etat.get("statut"));
        assertNull(df.getEmailStatut());
    }

    @Test
    void sans_decision_rien_n_est_envoye() throws Exception {
        assertEquals("AUCUN", service.notifier(dossier, df, new HashMap<>()).get("statut"));
        verify(emailService, never()).envoyer(any(), any());
    }

    @Test
    void envoi_automatique_desactive() throws Exception {
        ReflectionTestUtils.setField(service, "autoEnvoi", false);

        Map<String, Object> etat = service.notifier(dossier, df, resultat("ELIGIBLE"));

        verify(emailService, never()).envoyer(any(), any());
        assertEquals("DESACTIVE", etat.get("statut"));
        assertTrue(String.valueOf(etat.get("detail")).contains("Envoyer la réponse au client"));
    }

    // ── Échecs ───────────────────────────────────────────────────────────────

    @Test
    void echec_d_envoi_est_enregistre_sans_lever_d_exception() throws Exception {
        when(emailService.envoyer(any(), any())).thenThrow(new RuntimeException("SMTP indisponible"));

        Map<String, Object> etat = assertDoesNotThrow(() -> service.notifier(dossier, df, resultat("ELIGIBLE")));

        assertEquals("ECHEC", etat.get("statut"));
        assertEquals("ECHEC", df.getEmailStatut());
        assertEquals("SMTP indisponible", df.getEmailErreur());
        assertNull(df.getEmailEnvoyeAt());
        assertTrue(String.valueOf(etat.get("detail")).contains("SMTP indisponible"));
        verify(repository).save(df);
    }

    @Test
    void apres_un_echec_la_prochaine_analyse_retente_l_envoi() throws Exception {
        when(emailService.envoyer(any(), any()))
                .thenThrow(new RuntimeException("SMTP indisponible"))
                .thenAnswer(i -> new ResultatEmailService.Envoi("client@example.com", "ELIGIBLE", true));

        service.notifier(dossier, df, resultat("ELIGIBLE"));
        Map<String, Object> seconde = service.notifier(dossier, df, resultat("ELIGIBLE"));

        verify(emailService, times(2)).envoyer(any(), any());      // un échec ne bloque pas le renvoi
        assertEquals("ENVOYE", seconde.get("statut"));
        assertNull(df.getEmailErreur());
    }

    @Test
    void client_sans_adresse_e_mail_n_est_pas_une_erreur_technique() throws Exception {
        when(emailService.envoyer(any(), any())).thenThrow(new IllegalStateException("Email client introuvable"));

        Map<String, Object> etat = service.notifier(dossier, df, resultat("ELIGIBLE"));

        assertEquals("NON_ENVOYE", etat.get("statut"));
        assertEquals("Email client introuvable", df.getEmailErreur());
    }

    @Test
    void un_message_d_erreur_tres_long_est_tronque() throws Exception {
        when(emailService.envoyer(any(), any())).thenThrow(new RuntimeException("x".repeat(5000)));

        service.notifier(dossier, df, resultat("ELIGIBLE"));

        assertTrue(df.getEmailErreur().length() <= 500);   // la colonne fait 500 caractères
    }

    @Test
    void meme_la_base_en_panne_ne_fait_pas_echouer_l_analyse() {
        when(repository.save(any())).thenThrow(new RuntimeException("base indisponible"));

        Map<String, Object> etat = assertDoesNotThrow(() -> service.notifier(dossier, df, resultat("ELIGIBLE")));

        assertEquals("ECHEC", etat.get("statut"));
    }

    // ── État affiché à l'agent ───────────────────────────────────────────────

    @Test
    void etat_d_une_decision_sans_envoi_tente() {
        assertNull(service.etatDe(null));
        assertNull(service.etatDe(df));
    }

    @Test
    void etat_d_une_decision_deja_envoyee_ou_en_echec() {
        service.marquerEnvoye(df, new ResultatEmailService.Envoi("client@example.com", "REFUS", true));
        assertEquals("ENVOYE", service.etatDe(df).get("statut"));
        assertEquals("REFUS", service.etatDe(df).get("decision"));

        df.setEmailStatut("ECHEC");
        df.setEmailErreur("SMTP indisponible");
        assertTrue(String.valueOf(service.etatDe(df).get("detail")).contains("SMTP indisponible"));
    }

    @Test
    void le_bouton_manuel_efface_une_erreur_precedente() {
        df.setEmailStatut("ECHEC");
        df.setEmailErreur("SMTP indisponible");

        service.marquerEnvoye(df, new ResultatEmailService.Envoi("client@example.com", "ELIGIBLE", true));

        assertEquals("ENVOYE", df.getEmailStatut());
        assertNull(df.getEmailErreur());
    }
}
