package com.example.crediSense.jwt;

import com.example.crediSense.Service.impl.PropositionClientService;
import com.example.crediSense.controller.Client.ClientPropositionController;
import com.example.crediSense.entity.Agent;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.RoleType;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.ClientRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Règles d'accès réelles (SecurityConfig + JwtAuthFilter) : les routes de réponse aux propositions sont
 * réservées aux clients connectés, et un client connecté n'atteint AUCUNE route d'agent.
 *
 * Un code 404 signifie « la sécurité a laissé passer, mais aucune route ne correspond » ; 401 et 403
 * signifient « bloqué par la sécurité ».
 */
@WebMvcTest(controllers = ClientPropositionController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class SecurityConfigAccesTest {

    @Autowired MockMvc mvc;

    @MockBean JwtUtil jwtUtil;
    @MockBean AgentRepository agentRepository;
    @MockBean ClientRepository clientRepository;
    @MockBean DossierRepository dossierRepository;
    @MockBean DecisionFinaleRepository decisionFinaleRepository;
    @MockBean PropositionClientService propositionClientService;

    @BeforeEach
    void jetons() {
        for (String jeton : new String[]{"jc", "ja", "jad"}) when(jwtUtil.validateToken(jeton)).thenReturn(true);
        when(jwtUtil.extractEmail("jc")).thenReturn("marie@example.com");
        when(jwtUtil.extractRole("jc")).thenReturn("CLIENT");
        when(jwtUtil.extractEmail("ja")).thenReturn("agent@attijari.com");
        when(jwtUtil.extractRole("ja")).thenReturn("AGENT");
        when(jwtUtil.extractEmail("jad")).thenReturn("admin@attijari.com");
        when(jwtUtil.extractRole("jad")).thenReturn("ADMIN");

        Client marie = new Client();
        marie.setId(UUID.randomUUID());
        marie.setEmail("marie@example.com");
        when(clientRepository.findByEmail("marie@example.com")).thenReturn(Optional.of(marie));
        when(propositionClientService.propositionsDuClient(marie)).thenReturn(List.of());

        Agent agent = new Agent();
        agent.setEmail("agent@attijari.com");
        agent.setRole(RoleType.AGENT);
        when(agentRepository.findByEmail("agent@attijari.com")).thenReturn(Optional.of(agent));
        Agent admin = new Agent();
        admin.setEmail("admin@attijari.com");
        admin.setRole(RoleType.ADMIN);
        when(agentRepository.findByEmail("admin@attijari.com")).thenReturn(Optional.of(admin));
    }

    private int statut(MockHttpServletRequestBuilder requete, String jeton) throws Exception {
        if (jeton != null) requete.header("Authorization", "Bearer " + jeton);
        return mvc.perform(requete).andReturn().getResponse().getStatus();
    }

    private boolean bloque(int statut) { return statut == 401 || statut == 403; }

    // ── Routes de réponse aux propositions : le client connecté seulement ────

    @Test
    void leClientConnecteAccedeAuxRoutesDeProposition() throws Exception {
        assertTrue(statut(get("/api/clients/mes-demandes/propositions"), "jc") == 200);
    }

    @Test
    void sansJetonLesRoutesDePropositionSontFermees() throws Exception {
        assertTrue(bloque(statut(get("/api/clients/mes-demandes/propositions"), null)));
        assertTrue(bloque(statut(get("/api/clients/mes-demandes/" + UUID.randomUUID() + "/proposition"), null)));
        assertTrue(bloque(statut(post("/api/clients/mes-demandes/" + UUID.randomUUID() + "/proposition/repondre")
                .contentType("application/json").content("{\"choix\":\"ACCEPTER\",\"offre\":0}"), null)));
    }

    @Test
    void unAgentOuUnAdminNePeutPasRepondreALaPlaceDUnClient() throws Exception {
        assertTrue(bloque(statut(get("/api/clients/mes-demandes/propositions"), "ja")));
        assertTrue(bloque(statut(get("/api/clients/mes-demandes/propositions"), "jad")));
    }

    @Test
    void unJetonInconnuNOuvreRien() throws Exception {
        assertTrue(bloque(statut(get("/api/clients/mes-demandes/propositions"), "inconnu")));
    }

    // ── Un client connecté n'atteint aucune route d'agent ────────────────────

    @Test
    void unClientConnecteNeVoitPasLesDossiersDesAgents() throws Exception {
        assertTrue(bloque(statut(get("/api/dossiers/" + UUID.randomUUID() + "/resultat"), "jc")));
        assertTrue(bloque(statut(get("/api/dossiers/" + UUID.randomUUID() + "/audit"), "jc")));
        assertTrue(bloque(statut(get("/api/dossiers/" + UUID.randomUUID() + "/rapport-pdf"), "jc")));
        assertTrue(bloque(statut(post("/api/dossiers/" + UUID.randomUUID() + "/reponse/valider")
                .contentType("application/json").content("{}"), "jc")));
    }

    @Test
    void unClientConnecteNAccedeNiALAdministrationNiAuResteDeLApiProtegee() throws Exception {
        assertTrue(bloque(statut(get("/api/admin/statistiques"), "jc")));
        assertTrue(bloque(statut(get("/api/agents"), "jc")));
        assertTrue(bloque(statut(get("/api/auth/register"), "jc")));
    }

    @Test
    void lesAgentsEtAdminsPassentToujoursLaSecuriteDesRoutesDossiers() throws Exception {
        // 404 : la sécurité laisse passer (aucune route de ce nom dans cette tranche de test)
        assertTrue(statut(get("/api/dossiers/" + UUID.randomUUID() + "/resultat"), "ja") == 404);
        assertTrue(statut(get("/api/dossiers/" + UUID.randomUUID() + "/resultat"), "jad") == 404);
        assertTrue(statut(get("/api/admin/statistiques"), "jad") == 404);
        assertTrue(bloque(statut(get("/api/admin/statistiques"), "ja")), "l'administration reste réservée à ADMIN");
    }

    @Test
    void sansJetonLesRoutesDesAgentsRestentFermees() throws Exception {
        assertTrue(bloque(statut(get("/api/dossiers/" + UUID.randomUUID() + "/resultat"), null)));
        assertTrue(bloque(statut(get("/api/agents"), null)));
    }

    // ── Les routes publiques du portail ne changent pas ──────────────────────

    @Test
    void lesRoutesPubliquesDuPortailRestentOuvertes() throws Exception {
        assertTrue(statut(get("/api/clients/historique"), null) == 404);         // passe la sécurité (aucune route ici)
        assertTrue(statut(get("/api/public/demande/" + UUID.randomUUID() + "/fichiers"), null) == 404);
        assertTrue(statut(get("/api/clients/historique"), "jc") == 404);         // un jeton de client y reste accepté
    }
}
