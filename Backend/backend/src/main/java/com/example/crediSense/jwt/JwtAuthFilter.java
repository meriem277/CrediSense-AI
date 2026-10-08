package com.example.crediSense.jwt;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.crediSense.entity.Agent;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.ClientRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import java.util.List;
@Component
@RequiredArgsConstructor
public class JwtAuthFilter  extends OncePerRequestFilter {
     private final JwtUtil jwtUtil;
    private final AgentRepository agentRepository;
    private final ClientRepository clientRepository;


    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, java.io.IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/public/") ||
                path.startsWith("/api/client-auth/") ||
                path.startsWith("/api/auth/login") ||
                path.startsWith("/api/auth/init-admin")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            String authHeader = request.getHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (jwtUtil.validateToken(token)) {
                    String email = jwtUtil.extractEmail(token);

                    // Jeton d'un CLIENT : il n'ouvre que les routes réservées au rôle CLIENT (ses propres
                    // demandes). Il ne donne aucun accès aux routes des agents.
                    boolean jetonClient = "CLIENT".equals(jwtUtil.extractRole(token));
                    if (jetonClient && clientRepository.findByEmail(email).isPresent()) {
                        SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        email, null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))));
                    }

                    Agent agent = jetonClient ? null : agentRepository.findByEmail(email).orElse(null);
                    if (agent != null) {
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(
                                        agent.getEmail(),  // ✅ String au lieu de l'objet Agent
                                        null,
                                        List.of(new SimpleGrantedAuthority("ROLE_" + agent.getRole().name()))
                                );
                        SecurityContextHolder.getContext().setAuthentication(auth);

            }}}
        } catch (Exception e) {
            // ✅ Nettoie le contexte sans bloquer la chaîne
            SecurityContextHolder.clearContext();
        }

        // ✅ Toujours appelé, une seule fois
        chain.doFilter(request, response);
    }

    
}
