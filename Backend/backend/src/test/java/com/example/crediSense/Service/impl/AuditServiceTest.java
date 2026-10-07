package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.AuditEvent;
import com.example.crediSense.repository.AuditEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Le journal d'audit : qui a fait quoi, quand, avec quelle version des règles. Il ne doit jamais
 * faire échouer l'opération qu'il décrit, et ses phrases doivent se lire sans connaître le code.
 */
class AuditServiceTest {

    private AuditEventRepository repository;
    private AuditService service;
    private final UUID dossier = UUID.randomUUID();

    @BeforeEach
    void preparer() {
        repository = mock(AuditEventRepository.class);
        service = new AuditService(repository);
    }

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
    }

    private void connecte(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
    }

    private AuditEvent enregistre() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    // ── Qui ──────────────────────────────────────────────────────────────────

    @Test
    void l_acteur_est_l_agent_connecte() {
        connecte("agent@attijari.com");

        service.enregistrer(dossier, AuditService.ANALYSE, "ELIGIBLE", 78.0, "2026-10-a", Map.of("provider", "groq"));

        assertEquals("agent@attijari.com", enregistre().getActeur());
    }

    @Test
    void sans_agent_connecte_l_acteur_est_le_systeme() {
        service.enregistrer(dossier, AuditService.EMAIL_ENVOYE, "REFUS", null, null, null);

        assertEquals("SYSTEME", enregistre().getActeur());
    }

    @Test
    void une_requete_anonyme_n_est_pas_un_agent() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "cle", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertEquals("SYSTEME", service.acteurCourant());
    }

    // ── Quoi, quand, avec quelle version ─────────────────────────────────────

    @Test
    void l_evenement_garde_la_decision_le_score_la_version_des_regles_et_le_detail() {
        service.enregistrer(dossier, AuditService.ANALYSE, "ELIGIBLE", 78.0, "2026-10-a",
                Map.of("provider", "groq", "tauxAnnuelApplique", 0.1));

        AuditEvent e = enregistre();
        assertEquals(dossier, e.getDossierId());
        assertEquals("ANALYSE", e.getType());
        assertEquals("ELIGIBLE", e.getDecision());
        assertEquals(78.0, e.getScore());
        assertEquals("2026-10-a", e.getVersionRegles());
        assertTrue(e.getDetail().contains("\"provider\":\"groq\""));
    }

    @Test
    void sans_detail_la_colonne_reste_vide() {
        service.enregistrer(dossier, AuditService.EMAIL_ANNULE, "REFUS", null, null, Map.of());

        assertNull(enregistre().getDetail());
    }

    // ── Robustesse : le journal ne fait jamais échouer l'opération décrite ───

    @Test
    void une_base_en_panne_ne_fait_pas_echouer_l_operation() {
        when(repository.save(any())).thenThrow(new RuntimeException("base indisponible"));

        assertDoesNotThrow(() -> service.enregistrer(dossier, AuditService.ANALYSE, "ELIGIBLE", 70.0, "v", null));
    }

    // ── Lecture du journal ───────────────────────────────────────────────────

    private AuditEvent evenement(String type, String acteur, String decision, Double score, String detail) {
        return AuditEvent.builder().id(UUID.randomUUID()).dossierId(dossier).type(type).acteur(acteur)
                .decision(decision).score(score).versionRegles("2026-10-a").detail(detail)
                .createdAt(LocalDateTime.of(2026, 10, 7, 22, 10)).build();
    }

    @Test
    void le_journal_est_rendu_dans_l_ordre_avec_une_phrase_lisible() {
        when(repository.findByDossierIdOrderByCreatedAtAsc(dossier)).thenReturn(List.of(
                evenement("ANALYSE", "agent@attijari.com", "REFUS", 30.0, "{\"provider\":\"groq\"}"),
                evenement("EMAIL_EN_ATTENTE", "SYSTEME", "REFUS", null, "{\"raison\":\"validation requise\"}"),
                evenement("EMAIL_VALIDE", "agent@attijari.com", "REFUS", null, null)));

        List<Map<String, Object>> journal = service.journal(dossier);

        assertEquals(3, journal.size());
        assertEquals("ANALYSE", journal.get(0).get("type"));
        assertEquals("2026-10-07T22:10", journal.get(0).get("date"));
        assertEquals("2026-10-a", journal.get(0).get("versionRegles"));
        assertEquals("Analyse terminée : décision REFUS, score 30/100 (IA : groq)", journal.get(0).get("libelle"));
        assertEquals("Réponse REFUS mise en attente : validation requise", journal.get(1).get("libelle"));
        assertEquals("Envoi de la réponse validé par agent@attijari.com", journal.get(2).get("libelle"));
    }

    @Test
    void un_detail_illisible_ne_fait_pas_echouer_la_lecture() {
        when(repository.findByDossierIdOrderByCreatedAtAsc(dossier)).thenReturn(List.of(
                evenement("EMAIL_ECHEC", "SYSTEME", "ELIGIBLE", null, "{pas du json")));

        List<Map<String, Object>> journal = service.journal(dossier);

        assertEquals(1, journal.size());
        assertEquals(Map.of(), journal.get(0).get("detail"));
        assertTrue(String.valueOf(journal.get(0).get("libelle")).startsWith("Échec de l'envoi"));
    }

    @Test
    void dossier_sans_evenement_journal_vide() {
        when(repository.findByDossierIdOrderByCreatedAtAsc(dossier)).thenReturn(List.of());

        assertEquals(List.of(), service.journal(dossier));
    }

    // ── Les phrases ──────────────────────────────────────────────────────────

    private String libelle(String type, String acteur, String decision, Double score, Map<String, Object> detail) {
        return AuditService.libelle(type, acteur, decision, score, detail);
    }

    @Test
    void phrase_de_l_analyse_sans_score_pour_un_dossier_a_completer() {
        assertEquals("Analyse terminée : décision À COMPLÉTER",
                libelle("ANALYSE", "SYSTEME", "A_COMPLETER", 59.0, Map.of()));
    }

    @Test
    void phrase_de_l_envoi_dit_a_qui_comment_et_avec_le_pdf() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("destinataire", "client@example.com");
        d.put("mode", "VALIDATION");
        d.put("pdfJoint", true);

        assertEquals("Réponse REFUS envoyée à client@example.com (après validation), rapport PDF joint",
                libelle("EMAIL_ENVOYE", "agent@attijari.com", "REFUS", null, d));
        d.put("mode", "AUTO");
        d.put("pdfJoint", false);
        assertEquals("Réponse REFUS envoyée à client@example.com (envoi automatique)",
                libelle("EMAIL_ENVOYE", "SYSTEME", "REFUS", null, d));
    }

    @Test
    void phrase_de_l_annulation_dit_que_le_client_n_a_rien_recu() {
        assertEquals("Envoi de la réponse annulé par agent@attijari.com : le client n'a rien reçu",
                libelle("EMAIL_ANNULE", "agent@attijari.com", "REFUS", null, Map.of()));
    }

    @Test
    void phrase_de_l_echec_donne_la_raison() {
        assertEquals("Échec de l'envoi de la réponse : SMTP indisponible",
                libelle("EMAIL_ECHEC", "SYSTEME", "ELIGIBLE", null, Map.of("erreur", "SMTP indisponible")));
    }

    @Test
    void phrase_de_la_confirmation_d_incoherence() {
        assertEquals("Incohérence confirmée manuellement par agent@attijari.com : CIN incohérent sur [CIN]",
                libelle("INCOHERENCE_CONFIRMEE", "agent@attijari.com", null, null,
                        Map.of("details", "CIN incohérent sur [CIN]")));
    }

    @Test
    void phrases_du_rapport_et_du_statut() {
        assertEquals("Rapport PDF « client » téléchargé par agent@attijari.com",
                libelle("RAPPORT_TELECHARGE", "agent@attijari.com", null, null, Map.of("version", "client")));
        assertEquals("Statut du dossier modifié par agent@attijari.com : EN_COURS → APPROUVE",
                libelle("STATUT_MODIFIE", "agent@attijari.com", null, null,
                        Map.of("ancien", "EN_COURS", "nouveau", "APPROUVE")));
    }

    @Test
    void type_inconnu_affiche_le_type() {
        assertEquals("AUTRE_CHOSE", libelle("AUTRE_CHOSE", "SYSTEME", null, null, Map.of()));
        assertEquals("", libelle(null, "SYSTEME", null, null, Map.of()));
    }

    // ── En ajout seul ────────────────────────────────────────────────────────

    @Test
    void l_entite_est_immuable_et_le_service_n_expose_aucune_suppression() {
        assertTrue(AuditEvent.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class));
        for (var methode : AuditService.class.getDeclaredMethods()) {
            String nom = methode.getName().toLowerCase();
            assertFalse(nom.contains("supprimer") || nom.contains("delete") || nom.contains("modifier"),
                    "le journal est en ajout seul : " + methode.getName());
        }
    }
}
