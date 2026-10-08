package com.example.crediSense.jwt;

import com.example.crediSense.entity.Agent;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.RoleType;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.ClientRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Le filtre reconnaît les jetons des agents ET ceux des clients, mais un jeton de client ne donne que le
 * rôle CLIENT : jamais un rôle d'agent.
 */
class JwtAuthFilterTest {

    private JwtUtil jwtUtil;
    private AgentRepository agentRepository;
    private ClientRepository clientRepository;
    private JwtAuthFilter filtre;
    private FilterChain chaine;

    @BeforeEach
    void preparer() {
        SecurityContextHolder.clearContext();
        jwtUtil = mock(JwtUtil.class);
        agentRepository = mock(AgentRepository.class);
        clientRepository = mock(ClientRepository.class);
        chaine = mock(FilterChain.class);
        filtre = new JwtAuthFilter(jwtUtil, agentRepository, clientRepository);
    }

    @AfterEach
    void nettoyer() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requete(String chemin, String jeton) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", chemin);
        r.setRequestURI(chemin);
        if (jeton != null) r.addHeader("Authorization", "Bearer " + jeton);
        return r;
    }

    private void jetonValide(String jeton, String email, String role) {
        when(jwtUtil.validateToken(jeton)).thenReturn(true);
        when(jwtUtil.extractEmail(jeton)).thenReturn(email);
        when(jwtUtil.extractRole(jeton)).thenReturn(role);
    }

    private Agent agent(RoleType role) {
        Agent a = new Agent();
        a.setEmail("agent@attijari.com");
        a.setRole(role);
        return a;
    }

    @Test
    void unJetonDeClientDonneLeRoleClientEtRienDAutre() throws Exception {
        jetonValide("jc", "marie@example.com", "CLIENT");
        when(clientRepository.findByEmail("marie@example.com")).thenReturn(Optional.of(new Client()));

        filtre.doFilter(requete("/api/clients/mes-demandes/propositions", "jc"), new MockHttpServletResponse(), chaine);

        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(a);
        assertEquals("marie@example.com", a.getName());
        assertEquals(1, a.getAuthorities().size());
        assertEquals("ROLE_CLIENT", a.getAuthorities().iterator().next().getAuthority());
        verifyNoInteractions(agentRepository);                         // un client n'est jamais cherché parmi les agents
        verify(chaine).doFilter(any(), any());
    }

    @Test
    void unJetonDeClientPourUnCompteSupprimeNOuvreRien() throws Exception {
        jetonValide("jc", "fantome@example.com", "CLIENT");
        when(clientRepository.findByEmail("fantome@example.com")).thenReturn(Optional.empty());

        filtre.doFilter(requete("/api/clients/mes-demandes/propositions", "jc"), new MockHttpServletResponse(), chaine);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chaine).doFilter(any(), any());
    }

    @Test
    void unJetonDeClientNeSeFaitJamaisPasserPourUnAgentMemeAvecLeMemeEmail() throws Exception {
        // même adresse e-mail qu'un agent : le jeton reste celui d'un client
        jetonValide("jc", "agent@attijari.com", "CLIENT");
        when(clientRepository.findByEmail("agent@attijari.com")).thenReturn(Optional.of(new Client()));
        when(agentRepository.findByEmail("agent@attijari.com")).thenReturn(Optional.of(agent(RoleType.ADMIN)));

        filtre.doFilter(requete("/api/dossiers/1/resultat", "jc"), new MockHttpServletResponse(), chaine);

        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("ROLE_CLIENT", a.getAuthorities().iterator().next().getAuthority());
        assertTrue(a.getAuthorities().stream().noneMatch(g -> g.getAuthority().equals("ROLE_ADMIN") || g.getAuthority().equals("ROLE_AGENT")));
    }

    @Test
    void unJetonDAgentGardeSonRole() throws Exception {
        jetonValide("ja", "agent@attijari.com", "AGENT");
        when(agentRepository.findByEmail("agent@attijari.com")).thenReturn(Optional.of(agent(RoleType.AGENT)));

        filtre.doFilter(requete("/api/dossiers/1/resultat", "ja"), new MockHttpServletResponse(), chaine);

        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("agent@attijari.com", a.getName());
        assertEquals("ROLE_AGENT", a.getAuthorities().iterator().next().getAuthority());
        verifyNoInteractions(clientRepository);
    }

    @Test
    void unJetonDAdministrateurGardeSonRole() throws Exception {
        jetonValide("jad", "agent@attijari.com", "ADMIN");
        when(agentRepository.findByEmail("agent@attijari.com")).thenReturn(Optional.of(agent(RoleType.ADMIN)));

        filtre.doFilter(requete("/api/admin/statistiques", "jad"), new MockHttpServletResponse(), chaine);

        assertEquals("ROLE_ADMIN", SecurityContextHolder.getContext().getAuthentication().getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void unJetonInvalideOuAbsentNAuthentifiePersonne() throws Exception {
        when(jwtUtil.validateToken("mauvais")).thenReturn(false);

        filtre.doFilter(requete("/api/dossiers/1", "mauvais"), new MockHttpServletResponse(), chaine);
        assertNull(SecurityContextHolder.getContext().getAuthentication());

        filtre.doFilter(requete("/api/dossiers/1", null), new MockHttpServletResponse(), chaine);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chaine, times(2)).doFilter(any(), any());
    }

    @Test
    void uneErreurDeLectureDuJetonNeBloquePasLaChaine() throws Exception {
        when(jwtUtil.validateToken("casse")).thenReturn(true);
        when(jwtUtil.extractEmail("casse")).thenThrow(new RuntimeException("jeton illisible"));

        filtre.doFilter(requete("/api/dossiers/1", "casse"), new MockHttpServletResponse(), chaine);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chaine).doFilter(any(), any());
    }

    @Test
    void lesRoutesPubliquesNeLisentPasLeJeton() throws Exception {
        filtre.doFilter(requete("/api/public/upload", "jc"), new MockHttpServletResponse(), chaine);
        filtre.doFilter(requete("/api/client-auth/login", "jc"), new MockHttpServletResponse(), chaine);
        verifyNoInteractions(jwtUtil);
        verify(chaine, times(2)).doFilter(any(), any());
    }
}
