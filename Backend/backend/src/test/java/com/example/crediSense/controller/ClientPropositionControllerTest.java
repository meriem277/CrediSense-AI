package com.example.crediSense.controller;

import com.example.crediSense.Service.impl.AuditService;
import com.example.crediSense.Service.impl.PropositionClientService;
import com.example.crediSense.controller.Client.ClientPropositionController;
import com.example.crediSense.controller.Client.ClientPropositionController.ReponseRequest;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.ClientRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Routes du client connecté pour répondre à une proposition : réservées à son propre dossier, et réponses
 * d'erreur claires (404 / 400 / 409).
 */
class ClientPropositionControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClientRepository clientRepository;
    private DossierRepository dossierRepository;
    private DecisionFinaleRepository decisionRepository;
    private ClientPropositionController controleur;
    private Client marie;
    private Client autreClient;
    private Dossier dossierDeMarie;
    private DecisionFinale decision;

    @BeforeEach
    void preparer() throws Exception {
        clientRepository = mock(ClientRepository.class);
        dossierRepository = mock(DossierRepository.class);
        decisionRepository = mock(DecisionFinaleRepository.class);
        PropositionClientService service = new PropositionClientService(dossierRepository, decisionRepository, mock(AuditService.class));
        controleur = new ClientPropositionController(service, clientRepository, dossierRepository, decisionRepository);

        marie = new Client();
        marie.setId(UUID.randomUUID());
        marie.setEmail("marie@example.com");
        autreClient = new Client();
        autreClient.setId(UUID.randomUUID());
        autreClient.setEmail("autre@example.com");
        when(clientRepository.findByEmail("marie@example.com")).thenReturn(Optional.of(marie));
        when(clientRepository.findByEmail("autre@example.com")).thenReturn(Optional.of(autreClient));

        dossierDeMarie = Dossier.builder().id(UUID.randomUUID()).client(marie).build();
        when(dossierRepository.findById(dossierDeMarie.getId())).thenReturn(Optional.of(dossierDeMarie));

        Map<String, Object> resultat = Map.of("eligibility", "CONDITIONNEL", "versionRegles", "2026-10-b",
                "adjustedOffers", Map.of("applicable", true, "message", "m", "unresolved", List.of(), "offers", List.of(
                        Map.of("kind", "MONTANT_REDUIT", "label", "Montant réduit", "amount", 16300, "duration", 12,
                               "monthlyPayment", 1433.0, "dti", 29.85, "totalCost", 17196.0, "explanation", "e"),
                        Map.of("kind", "DUREE_ALLONGEE", "label", "Durée allongée", "amount", 20000, "duration", 18,
                               "monthlyPayment", 1201.0, "dti", 25.02, "totalCost", 21620.0, "explanation", "e"))));
        decision = DecisionFinale.builder().decisionFinale("CONDITIONNEL").emailStatut("ENVOYE").scoreFinal(58.0)
                .resultatComplet(MAPPER.writeValueAsString(resultat)).build();
        when(decisionRepository.findByDossierId(dossierDeMarie.getId())).thenReturn(Optional.of(decision));
    }

    private Authentication connecte(String email) {
        return new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
    }

    // ── Voir la proposition ──────────────────────────────────────────────────

    @Test
    void leClientVoitLaPropositionDeSaDemande() {
        ResponseEntity<?> r = controleur.proposition(dossierDeMarie.getId(), connecte("marie@example.com"));
        assertEquals(200, r.getStatusCode().value());
        Map<?, ?> corps = (Map<?, ?>) r.getBody();
        assertEquals("EN_ATTENTE_REPONSE", corps.get("etat"));
        assertEquals(2, ((List<?>) corps.get("offres")).size());
    }

    @Test
    void unAutreClientNeVoitPasLaProposition() {
        ResponseEntity<?> r = controleur.proposition(dossierDeMarie.getId(), connecte("autre@example.com"));
        assertEquals(404, r.getStatusCode().value());               // « introuvable » : on ne révèle pas que la demande existe
    }

    @Test
    void uneDemandeInconnueEstIntrouvable() {
        when(dossierRepository.findById(any())).thenReturn(Optional.empty());
        assertEquals(404, controleur.proposition(UUID.randomUUID(), connecte("marie@example.com")).getStatusCode().value());
    }

    @Test
    void sansProposition404() {
        decision.setEmailStatut("EN_ATTENTE_VALIDATION");
        assertEquals(404, controleur.proposition(dossierDeMarie.getId(), connecte("marie@example.com")).getStatusCode().value());
    }

    @Test
    void sansAuthentificationRienNEstDonne() {
        assertEquals(404, controleur.proposition(dossierDeMarie.getId(), null).getStatusCode().value());
        assertEquals(403, controleur.mesPropositions(null).getStatusCode().value());
    }

    @Test
    void unJetonDUnClientInconnuNOuvreRien() {
        assertEquals(404, controleur.proposition(dossierDeMarie.getId(), connecte("inconnu@example.com")).getStatusCode().value());
        assertEquals(403, controleur.mesPropositions(connecte("inconnu@example.com")).getStatusCode().value());
    }

    // ── Répondre ─────────────────────────────────────────────────────────────

    @Test
    void leClientAccepteUneOffre() {
        ResponseEntity<?> r = controleur.repondre(dossierDeMarie.getId(), new ReponseRequest("ACCEPTER", 1), connecte("marie@example.com"));
        assertEquals(200, r.getStatusCode().value());
        assertEquals("ACCEPTEE", ((Map<?, ?>) r.getBody()).get("etat"));
        assertEquals("ACCEPTEE", decision.getReponseClient());
        verify(decisionRepository).save(decision);
    }

    @Test
    void unAutreClientNePeutPasRepondreALaPlaceDuClient() {
        ResponseEntity<?> r = controleur.repondre(dossierDeMarie.getId(), new ReponseRequest("ACCEPTER", 0), connecte("autre@example.com"));
        assertEquals(404, r.getStatusCode().value());
        assertNull(decision.getReponseClient());
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void uneSecondeReponseRepond409AvecUnMessage() {
        controleur.repondre(dossierDeMarie.getId(), new ReponseRequest("REFUSER", null), connecte("marie@example.com"));
        ResponseEntity<?> r = controleur.repondre(dossierDeMarie.getId(), new ReponseRequest("ACCEPTER", 0), connecte("marie@example.com"));
        assertEquals(409, r.getStatusCode().value());
        assertTrue(((Map<?, ?>) r.getBody()).get("message").toString().contains("déjà répondu"));
        assertEquals("REFUSEE", decision.getReponseClient());
    }

    @Test
    void unChoixInvalideRepond400() {
        ResponseEntity<?> r = controleur.repondre(dossierDeMarie.getId(), new ReponseRequest("ACCEPTER", 7), connecte("marie@example.com"));
        assertEquals(400, r.getStatusCode().value());
        assertEquals(400, controleur.repondre(dossierDeMarie.getId(), new ReponseRequest("???", 0), connecte("marie@example.com")).getStatusCode().value());
        assertEquals(400, controleur.repondre(dossierDeMarie.getId(), null, connecte("marie@example.com")).getStatusCode().value());
        assertNull(decision.getReponseClient());
    }

    @Test
    void repondreSansDecisionEnregistree404() {
        when(decisionRepository.findByDossierId(dossierDeMarie.getId())).thenReturn(Optional.empty());
        assertEquals(404, controleur.repondre(dossierDeMarie.getId(), new ReponseRequest("REFUSER", null), connecte("marie@example.com")).getStatusCode().value());
    }

    // ── Liste ────────────────────────────────────────────────────────────────

    @Test
    void laListeNeMontreQueLesDemandesDuClientConnecte() {
        when(dossierRepository.findByClient(marie)).thenReturn(List.of(dossierDeMarie));
        when(dossierRepository.findByClient(autreClient)).thenReturn(List.of());

        ResponseEntity<List<Map<String, Object>>> maListe = controleur.mesPropositions(connecte("marie@example.com"));
        assertEquals(200, maListe.getStatusCode().value());
        assertEquals(1, maListe.getBody().size());
        assertEquals("EN_ATTENTE_REPONSE", maListe.getBody().get(0).get("etat"));

        assertTrue(controleur.mesPropositions(connecte("autre@example.com")).getBody().isEmpty());
    }
}
