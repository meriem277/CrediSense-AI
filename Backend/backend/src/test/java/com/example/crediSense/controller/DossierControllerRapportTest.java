package com.example.crediSense.controller;

import com.example.crediSense.Service.DossierService;
import com.example.crediSense.Service.impl.AuditService;
import com.example.crediSense.Service.impl.NotificationDecisionService;
import com.example.crediSense.Service.impl.RapportPdfService;
import com.example.crediSense.Service.impl.ResultatEmailService;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import com.example.crediSense.repository.JsonExtractionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Endpoints de la réponse au client : le résultat enregistré fait foi pour l'e-mail, le rapport PDF
 * se télécharge en deux versions, et l'état de l'envoi est remonté avec le résultat.
 */
class DossierControllerRapportTest {

    private final UUID dossierId = UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000");

    private DossierRepository          dossierRepository;
    private DecisionFinaleRepository   decisionRepository;
    private AuditService               auditService;
    private RapportPdfService          pdfService;
    private NotificationDecisionService notificationService;
    private DossierController          controller;
    private Dossier                    dossier;

    @BeforeEach
    void preparer() throws Exception {
        dossierRepository   = mock(DossierRepository.class);
        decisionRepository  = mock(DecisionFinaleRepository.class);
        auditService        = mock(AuditService.class);
        pdfService          = mock(RapportPdfService.class);
        notificationService = mock(NotificationDecisionService.class);

        Client client = new Client();
        client.setEmail("client@example.com");
        dossier = Dossier.builder().id(dossierId).client(client).build();
        when(dossierRepository.findById(dossierId)).thenReturn(Optional.of(dossier));
        when(notificationService.envoyerManuellement(any(), any(), any()))
                .thenReturn(new ResultatEmailService.Envoi("client@example.com", "REFUS", true));

        controller = new DossierController(
                mock(DossierService.class), dossierRepository, mock(AgentRepository.class),
                decisionRepository, mock(JsonExtractionRepository.class),
                pdfService, notificationService, auditService);
    }

    private DecisionFinale decisionEnregistree(String json) {
        DecisionFinale df = DecisionFinale.builder().id(UUID.randomUUID()).dossier(dossier).resultatComplet(json).build();
        when(decisionRepository.findByDossierId(dossierId)).thenReturn(Optional.of(df));
        return df;
    }

    // ── Envoi manuel ─────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l_e_mail_part_avec_le_resultat_enregistre_pas_avec_ce_que_le_navigateur_envoie() throws Exception {
        DecisionFinale df = decisionEnregistree("{\"eligibility\":\"REFUS\",\"eligibilityScore\":20}");
        Map<String, Object> falsifie = new HashMap<>();
        falsifie.put("eligibility", "ELIGIBLE");                  // requête modifiée pour tromper

        ResponseEntity<Map<String, Object>> reponse = controller.sendResultEmail(dossierId, falsifie);

        assertTrue(reponse.getStatusCode().is2xxSuccessful());
        ArgumentCaptor<Map<String, Object>> envoye = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).envoyerManuellement(eq(dossier), eq(df), envoye.capture());
        assertEquals("REFUS", envoye.getValue().get("eligibility"));
    }

    @Test
    void sans_resultat_enregistre_le_contenu_de_la_requete_sert_encore() throws Exception {
        when(decisionRepository.findByDossierId(dossierId)).thenReturn(Optional.empty());
        Map<String, Object> payload = new HashMap<>();
        payload.put("eligibility", "ELIGIBLE");

        ResponseEntity<Map<String, Object>> reponse = controller.sendResultEmail(dossierId, payload);

        assertTrue(reponse.getStatusCode().is2xxSuccessful());
        verify(notificationService).envoyerManuellement(eq(dossier), isNull(), eq(payload));
    }

    @Test
    void sans_aucun_resultat_rien_n_est_envoye() throws Exception {
        when(decisionRepository.findByDossierId(dossierId)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> reponse = controller.sendResultEmail(dossierId, null);

        assertEquals(400, reponse.getStatusCode().value());
        assertEquals(false, reponse.getBody().get("success"));
        verify(notificationService, never()).envoyerManuellement(any(), any(), any());
    }

    @Test
    void echec_d_envoi_est_rapporte_a_l_agent() throws Exception {
        decisionEnregistree("{\"eligibility\":\"REFUS\"}");
        when(notificationService.envoyerManuellement(any(), any(), any()))
                .thenThrow(new IllegalStateException("Email client introuvable"));

        ResponseEntity<Map<String, Object>> reponse = controller.sendResultEmail(dossierId, null);

        assertEquals(400, reponse.getStatusCode().value());
        assertEquals("Email client introuvable", reponse.getBody().get("error"));
    }

    // ── Rapport PDF ──────────────────────────────────────────────────────────

    @Test
    void le_rapport_agent_se_telecharge_en_pdf() {
        decisionEnregistree("{\"eligibility\":\"ELIGIBLE\"}");
        when(pdfService.generer(any(), any(), eq(RapportPdfService.Version.AGENT))).thenReturn("%PDF-agent".getBytes());

        ResponseEntity<byte[]> reponse = controller.rapportPdf(dossierId, "agent");

        assertEquals(200, reponse.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PDF, reponse.getHeaders().getContentType());
        assertEquals("attachment; filename=\"Rapport-credit-1A2B3C4D.pdf\"",
                reponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("%PDF-agent", new String(reponse.getBody()));
    }

    @Test
    void le_rapport_client_est_une_autre_version_avec_un_autre_nom() {
        decisionEnregistree("{\"eligibility\":\"ELIGIBLE\"}");
        when(pdfService.generer(any(), any(), eq(RapportPdfService.Version.CLIENT))).thenReturn("%PDF-client".getBytes());

        ResponseEntity<byte[]> reponse = controller.rapportPdf(dossierId, "client");

        assertEquals("%PDF-client", new String(reponse.getBody()));
        assertTrue(reponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("-client.pdf"));
    }

    @Test
    void une_version_inconnue_donne_le_rapport_agent_et_non_une_erreur() {
        decisionEnregistree("{\"eligibility\":\"ELIGIBLE\"}");
        when(pdfService.generer(any(), any(), any())).thenReturn("%PDF".getBytes());

        controller.rapportPdf(dossierId, "n-importe-quoi");

        verify(pdfService).generer(any(), any(), eq(RapportPdfService.Version.AGENT));
    }

    @Test
    void pas_de_resultat_pas_de_rapport() {
        when(decisionRepository.findByDossierId(dossierId)).thenReturn(Optional.empty());

        assertEquals(404, controller.rapportPdf(dossierId, "agent").getStatusCode().value());
        verifyNoInteractions(pdfService);
    }

    @Test
    void dossier_inconnu_pas_de_rapport() {
        UUID inconnu = UUID.randomUUID();
        when(dossierRepository.findById(inconnu)).thenReturn(Optional.empty());

        assertEquals(404, controller.rapportPdf(inconnu, "agent").getStatusCode().value());
    }

    @Test
    void resultat_illisible_pas_de_rapport() {
        decisionEnregistree("{pas du json");

        assertEquals(404, controller.rapportPdf(dossierId, "agent").getStatusCode().value());
    }

    // ── État de l'envoi dans le résultat ─────────────────────────────────────

    @Test
    void le_resultat_relu_contient_l_etat_de_l_envoi() {
        DecisionFinale df = decisionEnregistree("{\"eligibility\":\"ELIGIBLE\"}");
        Map<String, Object> etat = Map.of("statut", "ENVOYE", "destinataire", "client@example.com");
        when(notificationService.etatDe(df)).thenReturn(etat);

        ResponseEntity<Map<String, Object>> reponse = controller.getResultat(dossierId);

        assertEquals("ELIGIBLE", reponse.getBody().get("eligibility"));
        assertEquals(etat, reponse.getBody().get("notification"));
    }

    // ── Valider et envoyer / Ne pas envoyer ──────────────────────────────────

    private static Map<String, Object> etat(String statut) {
        Map<String, Object> e = new HashMap<>();
        e.put("statut", statut);
        e.put("detail", "détail " + statut);
        return e;
    }

    @Test
    void valider_transmet_la_decision_lue_et_confirme_l_envoi() {
        DecisionFinale df = decisionEnregistree("{\"eligibility\":\"REFUS\"}");
        when(notificationService.valider(eq(dossier), eq(df), any(), eq("REFUS"))).thenReturn(etat("ENVOYE"));

        ResponseEntity<Map<String, Object>> reponse = controller.validerReponse(dossierId, Map.of("decision", "REFUS"));

        assertEquals(200, reponse.getStatusCode().value());
        assertEquals(true, reponse.getBody().get("success"));
        assertEquals("ENVOYE", ((Map<?, ?>) reponse.getBody().get("notification")).get("statut"));
    }

    @Test
    void valider_dont_l_envoi_echoue_n_annonce_pas_un_succes() {
        decisionEnregistree("{\"eligibility\":\"REFUS\"}");
        when(notificationService.valider(any(), any(), any(), any())).thenReturn(etat("ECHEC"));

        ResponseEntity<Map<String, Object>> reponse = controller.validerReponse(dossierId, Map.of("decision", "REFUS"));

        assertEquals(200, reponse.getStatusCode().value());
        assertEquals(false, reponse.getBody().get("success"));
        assertEquals("détail ECHEC", reponse.getBody().get("message"));
    }

    @Test
    void valider_une_decision_qui_a_change_donne_409() {
        decisionEnregistree("{\"eligibility\":\"ELIGIBLE\"}");
        when(notificationService.valider(any(), any(), any(), any())).thenThrow(
                new NotificationDecisionService.ReponseNonModifiableException("La décision a changé depuis votre lecture"));

        ResponseEntity<Map<String, Object>> reponse = controller.validerReponse(dossierId, Map.of("decision", "REFUS"));

        assertEquals(409, reponse.getStatusCode().value());
        assertEquals(false, reponse.getBody().get("success"));
        assertTrue(String.valueOf(reponse.getBody().get("error")).contains("La décision a changé"));
    }

    @Test
    void valider_sans_corps_transmet_une_decision_absente() {
        decisionEnregistree("{\"eligibility\":\"REFUS\"}");
        when(notificationService.valider(any(), any(), any(), isNull())).thenThrow(
                new NotificationDecisionService.ReponseNonModifiableException("décision non précisée"));

        assertEquals(409, controller.validerReponse(dossierId, null).getStatusCode().value());
    }

    @Test
    void valider_sans_resultat_enregistre_donne_404() {
        when(decisionRepository.findByDossierId(dossierId)).thenReturn(Optional.empty());

        assertEquals(404, controller.validerReponse(dossierId, Map.of("decision", "REFUS")).getStatusCode().value());
        verifyNoInteractions(notificationService);
    }

    @Test
    void annuler_l_envoi() {
        DecisionFinale df = decisionEnregistree("{\"eligibility\":\"REFUS\"}");
        when(notificationService.annuler(dossier, df)).thenReturn(etat("ANNULE"));

        ResponseEntity<Map<String, Object>> reponse = controller.annulerReponse(dossierId);

        assertEquals(200, reponse.getStatusCode().value());
        assertEquals(true, reponse.getBody().get("success"));
        assertTrue(String.valueOf(reponse.getBody().get("message")).contains("ne recevra rien"));
    }

    @Test
    void annuler_quand_rien_n_est_en_attente_donne_409() {
        decisionEnregistree("{\"eligibility\":\"REFUS\"}");
        when(notificationService.annuler(any(), any())).thenThrow(
                new NotificationDecisionService.ReponseNonModifiableException("Rien à annuler"));

        assertEquals(409, controller.annulerReponse(dossierId).getStatusCode().value());
    }

    @Test
    void annuler_sans_resultat_enregistre_donne_404() {
        when(decisionRepository.findByDossierId(dossierId)).thenReturn(Optional.empty());

        assertEquals(404, controller.annulerReponse(dossierId).getStatusCode().value());
    }

    // ── Journal ──────────────────────────────────────────────────────────────

    @Test
    void le_journal_du_dossier_est_servi_tel_quel() {
        List<Map<String, Object>> lignes = List.of(Map.of("type", "ANALYSE", "libelle", "Analyse terminée"));
        when(auditService.journal(dossierId)).thenReturn(lignes);

        ResponseEntity<List<Map<String, Object>>> reponse = controller.journal(dossierId);

        assertEquals(200, reponse.getStatusCode().value());
        assertEquals(lignes, reponse.getBody());
    }

    @Test
    void telecharger_un_rapport_est_inscrit_au_journal() {
        decisionEnregistree("{\"eligibility\":\"ELIGIBLE\"}");
        when(pdfService.generer(any(), any(), any())).thenReturn("%PDF".getBytes());

        controller.rapportPdf(dossierId, "client");

        verify(auditService).enregistrer(eq(dossierId), eq("RAPPORT_TELECHARGE"), eq("ELIGIBLE"),
                isNull(), isNull(), eq(Map.of("version", "client")));
    }

    @Test
    void un_rapport_introuvable_n_est_pas_inscrit_au_journal() {
        when(decisionRepository.findByDossierId(dossierId)).thenReturn(Optional.empty());

        controller.rapportPdf(dossierId, "agent");

        verifyNoInteractions(auditService);
    }

    @Test
    void changer_le_statut_d_un_dossier_est_inscrit_au_journal() {
        dossier.setStatut("EN_COURS");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "agent@attijari.com", null, List.of()));
        try {
            controller.updateStatut(dossierId, Map.of("statut", "APPROUVE"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        verify(auditService).enregistrer(eq(dossierId), eq("STATUT_MODIFIE"), isNull(), isNull(), isNull(),
                eq(Map.of("ancien", "EN_COURS", "nouveau", "APPROUVE")));
    }
}
