package com.example.crediSense.jwt;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.crediSense.entity.Agent;
import com.example.crediSense.repository.AgentRepository;

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


    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, java.io.IOException {

        try {
            String authHeader = request.getHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (jwtUtil.validateToken(token)) {
                    String email = jwtUtil.extractEmail(token);
                    Agent agent = agentRepository.findByEmail(email).orElse(null);
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
