package com.example.crediSense.Service.impl;

import com.example.crediSense.Service.impl.PropositionClientService.PropositionException;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Propositions d'ajustement faites au client et sa réponse : quand elles sont visibles, ce que le client
 * voit, ce qu'il peut faire, et ce que devient sa réponse quand le dossier est ré-analysé.
 */
class PropositionClientServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DossierRepository dossierRepository;
    private DecisionFinaleRepository decisionRepository;
    private AuditService audit;
    private PropositionClientService service;
    private Dossier dossier;

    @BeforeEach
    void preparer() {
        dossierRepository = mock(DossierRepository.class);
        decisionRepository = mock(DecisionFinaleRepository.class);
        audit = mock(AuditService.class);
        service = new PropositionClientService(dossierRepository, decisionRepository, audit);
        Client client = new Client();
        client.setId(UUID.randomUUID());
        client.setEmail("client@example.com");
        dossier = Dossier.builder().id(UUID.randomUUID()).client(client).build();
    }

    private Map<String, Object> offre(String genre, String libelle, double montant, int duree) {
        Map<String, Object> o = new HashMap<>();
        o.put("kind", genre);
        o.put("label", libelle);
        o.put("amount", montant);
        o.put("duration", duree);
        o.put("monthlyPayment", 1200.0);
        o.put("dti", 25.0);
        o.put("totalCost", 21000.0);
        o.put("explanation", "texte");
        o.put("interne", "ne doit jamais être montré");
        return o;
    }

    private Map<String, Object> resultat(String decision, List<Map<String, Object>> offres, boolean applicable) {
        Map<String, Object> r = new HashMap<>();
        r.put("eligibility", decision);
        r.put("versionRegles", "2026-10-b");
        r.put("adjustedOffers", Map.of("applicable", applicable, "message", "Propositions indicatives.",
                "offers", offres, "unresolved", List.of()));
        return r;
    }

    private DecisionFinale decision(String decision, String emailStatut, Map<String, Object> resultat) {
        try {
            return DecisionFinale.builder().decisionFinale(decision).emailStatut(emailStatut)
                    .scoreFinal(58.0).resultatComplet(MAPPER.writeValueAsString(resultat)).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> deuxOffres() {
        return List.of(offre("MONTANT_REDUIT", "Montant réduit, même durée", 16300, 12),
                       offre("DUREE_ALLONGEE", "Même montant, durée allongée", 20000, 18));
    }

    private DecisionFinale conditionnelEnvoye() {
        return decision("CONDITIONNEL", "ENVOYE", resultat("CONDITIONNEL", deuxOffres(), true));
    }

    // ── Quand le client voit-il les propositions ? ───────────────────────────

    @Test
    void leClientVoitLesPropositionsQuandLaReponseEstEnvoyee() {
        Map<String, Object> vue = service.propositionPour(conditionnelEnvoye()).orElseThrow();

        assertEquals("CONDITIONNEL", vue.get("decision"));
        assertEquals("EN_ATTENTE_REPONSE", vue.get("etat"));
        assertEquals("Propositions indicatives.", vue.get("message"));
        assertEquals(2, ((List<?>) vue.get("offres")).size());
        assertNull(vue.get("choix"));
    }

    @Test
    void rienNEstVisibleTantQueLaReponseNEstPasPartie() {
        for (String statut : new String[]{"EN_ATTENTE_VALIDATION", "PROGRAMME", "ANNULE", "ECHEC", "NON_ENVOYE", null}) {
            DecisionFinale df = decision("CONDITIONNEL", statut, resultat("CONDITIONNEL", deuxOffres(), true));
            assertTrue(service.propositionPour(df).isEmpty(), "statut d'envoi : " + statut);
        }
    }

    @Test
    void rienNEstVisiblePourUneAutreDecision() {
        for (String d : new String[]{"ELIGIBLE", "REFUS", "A_COMPLETER", "INDETERMINE", null}) {
            DecisionFinale df = decision(d, "ENVOYE", resultat(d == null ? "X" : d, deuxOffres(), true));
            assertTrue(service.propositionPour(df).isEmpty(), "décision : " + d);
        }
    }

    @Test
    void rienNEstVisibleSansOffreApplicable() {
        assertTrue(service.propositionPour(decision("CONDITIONNEL", "ENVOYE", resultat("CONDITIONNEL", deuxOffres(), false))).isEmpty());
        assertTrue(service.propositionPour(decision("CONDITIONNEL", "ENVOYE", resultat("CONDITIONNEL", List.of(), true))).isEmpty());
        assertTrue(service.propositionPour(null).isEmpty());
    }

    @Test
    void unResultatIllisibleOuAbsentNePlantePas() {
        DecisionFinale illisible = DecisionFinale.builder().decisionFinale("CONDITIONNEL").emailStatut("ENVOYE")
                .resultatComplet("{pas du json").build();
        assertTrue(service.propositionPour(illisible).isEmpty());
        assertTrue(service.propositionPour(DecisionFinale.builder().decisionFinale("CONDITIONNEL").emailStatut("ENVOYE").build()).isEmpty());
    }

    @Test
    void seulsLesChampsPrevusPourLeClientSontExposes() {
        Map<String, Object> vue = service.propositionPour(conditionnelEnvoye()).orElseThrow();
        Map<?, ?> premiere = (Map<?, ?>) ((List<?>) vue.get("offres")).get(0);
        assertFalse(premiere.containsKey("interne"));
        assertEquals(List.of("kind", "label", "amount", "duration", "monthlyPayment", "dti", "totalCost", "explanation"),
                new ArrayList<>(premiere.keySet()));
    }

    @Test
    void uneOffreSansMontantNiDureeEstIgnoree() {
        Map<String, Object> incomplete = new HashMap<>();
        incomplete.put("label", "incomplète");
        DecisionFinale df = decision("CONDITIONNEL", "ENVOYE",
                resultat("CONDITIONNEL", List.of(incomplete, offre("MONTANT_REDUIT", "ok", 5000, 12)), true));
        assertEquals(1, ((List<?>) service.propositionPour(df).orElseThrow().get("offres")).size());
    }

    // ── La réponse du client ─────────────────────────────────────────────────

    @Test
    void leClientAccepteUneOffre() {
        DecisionFinale df = conditionnelEnvoye();

        Map<String, Object> vue = service.repondre(dossier, df, "ACCEPTER", 1);

        assertEquals("ACCEPTEE", df.getReponseClient());
        assertEquals(1, df.getOffreChoisieIndex());
        assertNotNull(df.getReponseClientLe());
        assertTrue(df.getOffreChoisieJson().contains("Même montant, durée allongée"));
        assertEquals("ACCEPTEE", vue.get("etat"));
        assertEquals(1, vue.get("choix"));
        verify(decisionRepository).save(df);
        verify(audit).enregistrer(eq(dossier.getId()), eq(AuditService.OFFRE_ACCEPTEE), eq("CONDITIONNEL"), eq(58.0),
                eq("2026-10-b"), argThat(d -> "Même montant, durée allongée".equals(d.get("label"))
                        && Double.valueOf(20000).equals(d.get("montant")) && Integer.valueOf(18).equals(d.get("duree"))));
    }

    @Test
    void leClientRefuseToutesLesPropositions() {
        DecisionFinale df = conditionnelEnvoye();

        Map<String, Object> vue = service.repondre(dossier, df, "refuser", null);

        assertEquals("REFUSEE", df.getReponseClient());
        assertNull(df.getOffreChoisieIndex());
        assertNull(df.getOffreChoisieJson());
        assertEquals("REFUSEE", vue.get("etat"));
        verify(audit).enregistrer(eq(dossier.getId()), eq(AuditService.OFFRE_REFUSEE), any(), any(), any(), any());
    }

    @Test
    void laReponseEstAccepteeEnMinusculesEtAvecDesEspaces() {
        DecisionFinale df = conditionnelEnvoye();
        service.repondre(dossier, df, "  accepter ", 0);
        assertEquals("ACCEPTEE", df.getReponseClient());
    }

    @Test
    void onNeRepondQuUneFois() {
        DecisionFinale df = conditionnelEnvoye();
        service.repondre(dossier, df, "ACCEPTER", 0);

        PropositionException e = assertThrows(PropositionException.class, () -> service.repondre(dossier, df, "REFUSER", null));
        assertEquals(409, e.getStatut());
        assertEquals("ACCEPTEE", df.getReponseClient(), "la première réponse est conservée");
        verify(decisionRepository, times(1)).save(any());
    }

    @Test
    void unChoixInvalideEstRefuseSansRienEnregistrer() {
        DecisionFinale df = conditionnelEnvoye();
        for (Integer numero : new Integer[]{null, -1, 2, 99}) {
            PropositionException e = assertThrows(PropositionException.class, () -> service.repondre(dossier, df, "ACCEPTER", numero));
            assertEquals(400, e.getStatut(), "offre " + numero);
        }
        for (String choix : new String[]{null, "", "PEUTETRE", "ok"}) {
            PropositionException e = assertThrows(PropositionException.class, () -> service.repondre(dossier, df, choix, 0));
            assertEquals(400, e.getStatut(), "choix " + choix);
        }
        assertNull(df.getReponseClient());
        verifyNoInteractions(audit);
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void onNeRepondPasQuandRienNEstPropose() {
        DecisionFinale df = decision("CONDITIONNEL", "EN_ATTENTE_VALIDATION", resultat("CONDITIONNEL", deuxOffres(), true));
        PropositionException e = assertThrows(PropositionException.class, () -> service.repondre(dossier, df, "ACCEPTER", 0));
        assertEquals(404, e.getStatut());
        assertNull(df.getReponseClient());
    }

    // ── Liste des demandes du client ─────────────────────────────────────────

    @Test
    void laListeNeContientQueLesDemandesAvecUneProposition() {
        Dossier autre = Dossier.builder().id(UUID.randomUUID()).client(dossier.getClient()).build();
        when(dossierRepository.findByClient(dossier.getClient())).thenReturn(List.of(dossier, autre));
        DecisionFinale avec = conditionnelEnvoye();
        avec.setReponseClient("REFUSEE");
        when(decisionRepository.findByDossierId(dossier.getId())).thenReturn(Optional.of(avec));
        when(decisionRepository.findByDossierId(autre.getId()))
                .thenReturn(Optional.of(decision("REFUS", "ENVOYE", resultat("REFUS", List.of(), false))));

        List<Map<String, Object>> liste = service.propositionsDuClient(dossier.getClient());

        assertEquals(1, liste.size());
        assertEquals(dossier.getId().toString(), liste.get(0).get("dossierId"));
        assertEquals("REFUSEE", liste.get(0).get("etat"));
    }

    @Test
    void uneDemandeSansDecisionNEstPasListee() {
        when(dossierRepository.findByClient(dossier.getClient())).thenReturn(List.of(dossier));
        when(decisionRepository.findByDossierId(dossier.getId())).thenReturn(Optional.empty());
        assertTrue(service.propositionsDuClient(dossier.getClient()).isEmpty());
    }

    // ── Ce que voit l'agent ──────────────────────────────────────────────────

    @Test
    void lAgentNeVoitRienTantQueLeClientNAPasRepondu() {
        assertNull(PropositionClientService.etatPourAgent(conditionnelEnvoye()));
        assertNull(PropositionClientService.etatPourAgent(null));
    }

    @Test
    void lAgentVoitLOffreAcceptee() {
        DecisionFinale df = conditionnelEnvoye();
        service.repondre(dossier, df, "ACCEPTER", 0);

        Map<String, Object> etat = PropositionClientService.etatPourAgent(df);

        assertEquals("ACCEPTEE", etat.get("statut"));
        assertNotNull(etat.get("repondueLe"));
        assertEquals("Montant réduit, même durée", ((Map<?, ?>) etat.get("offre")).get("label"));
    }

    @Test
    void lAgentVoitUnRefusSansOffre() {
        DecisionFinale df = conditionnelEnvoye();
        service.repondre(dossier, df, "REFUSER", null);
        Map<String, Object> etat = PropositionClientService.etatPourAgent(df);
        assertEquals("REFUSEE", etat.get("statut"));
        assertNull(etat.get("offre"));
    }

    // ── Nouvelle analyse ─────────────────────────────────────────────────────

    @Test
    void uneNouvelleAnalyseIdentiqueGardeLaReponse() {
        DecisionFinale df = conditionnelEnvoye();
        service.repondre(dossier, df, "ACCEPTER", 1);

        assertFalse(PropositionClientService.reinitialiserSiObsolete(df, resultat("CONDITIONNEL", deuxOffres(), true)));
        assertEquals("ACCEPTEE", df.getReponseClient());
    }

    @Test
    void uneNouvelleAnalyseQuiChangeLOffreChoisieEffaceLaReponse() {
        DecisionFinale df = conditionnelEnvoye();
        service.repondre(dossier, df, "ACCEPTER", 1);
        List<Map<String, Object>> autres = List.of(offre("MONTANT_REDUIT", "Montant réduit, même durée", 15000, 12));

        assertTrue(PropositionClientService.reinitialiserSiObsolete(df, resultat("CONDITIONNEL", autres, true)));

        assertNull(df.getReponseClient());
        assertNull(df.getOffreChoisieIndex());
        assertNull(df.getOffreChoisieJson());
        assertNull(df.getReponseClientLe());
    }

    @Test
    void uneNouvelleDecisionEffaceLaReponse() {
        DecisionFinale df = conditionnelEnvoye();
        service.repondre(dossier, df, "ACCEPTER", 0);

        assertTrue(PropositionClientService.reinitialiserSiObsolete(df, resultat("ELIGIBLE", List.of(), false)));
        assertNull(df.getReponseClient());
    }

    @Test
    void unRefusResteValableTantQueDesPropositionsExistent() {
        DecisionFinale df = conditionnelEnvoye();
        service.repondre(dossier, df, "REFUSER", null);

        assertFalse(PropositionClientService.reinitialiserSiObsolete(df, resultat("CONDITIONNEL", deuxOffres(), true)));
        assertEquals("REFUSEE", df.getReponseClient());
        assertTrue(PropositionClientService.reinitialiserSiObsolete(df, resultat("CONDITIONNEL", List.of(), false)));
        assertNull(df.getReponseClient());
    }

    @Test
    void sansReponseDuClientRienNEstEfface() {
        assertFalse(PropositionClientService.reinitialiserSiObsolete(conditionnelEnvoye(), resultat("ELIGIBLE", List.of(), false)));
        assertFalse(PropositionClientService.reinitialiserSiObsolete(null, resultat("ELIGIBLE", List.of(), false)));
    }

    @Test
    void lesLibellesDuJournalSontLisibles() {
        Map<String, Object> detail = Map.of("label", "Même montant, durée allongée", "montant", 20000.0, "duree", 18);
        assertTrue(AuditService.libelle(AuditService.OFFRE_ACCEPTEE, "client@example.com", "CONDITIONNEL", 58.0, detail)
                .contains("Offre acceptée par le client : Même montant, durée allongée"));
        assertEquals("Propositions refusées par le client",
                AuditService.libelle(AuditService.OFFRE_REFUSEE, "client@example.com", "CONDITIONNEL", 58.0, Map.of()));
        assertTrue(AuditService.libelle(AuditService.OFFRE_REINITIALISEE, "SYSTEME", "ELIGIBLE", 80.0, Map.of())
                .contains("réinitialisée"));
    }
}
