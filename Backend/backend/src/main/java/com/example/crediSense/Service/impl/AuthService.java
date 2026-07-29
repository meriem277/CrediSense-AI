package com.example.crediSense.Service.impl;

import com.example.crediSense.dto.request.*;
import com.example.crediSense.dto.response.AuthResponse;
import com.example.crediSense.entity.Agent;
import com.example.crediSense.entity.RoleType;
import com.example.crediSense.jwt.JwtUtil;
import com.example.crediSense.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AgentRepository agentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil         jwtUtil;

    // ── Login ─────────────────────────────────────────────────────────
    public AuthResponse login(LoginRequest request) {
        System.out.println("=== LOGIN ATTEMPT ===");
        System.out.println("Email reçu: '" + request.getEmail() + "'");

        Agent agent = agentRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Compte introuvable"));

        System.out.println("Agent trouvé: " + agent.getNom());
        System.out.println("Password match: " + passwordEncoder.matches(request.getPassword(), agent.getPassword()));

        if (!passwordEncoder.matches(request.getPassword(), agent.getPassword())) {
            throw new RuntimeException("Compte introuvable");
        }

        String token = jwtUtil.generateToken(agent);
        return new AuthResponse(agent.getId().toString(), token, agent.getRole().name());
    }

    // ── Register ──────────────────────────────────────────────────────
    public AuthResponse register(RegisterRequest request) {
        if (agentRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email déjà utilisé");
        }

        // ✅ Validation mot de passe
        validatePassword(request.getPassword());

        Agent agent = new Agent();
        agent.setNom(request.getNom());
        agent.setEmail(request.getEmail());
        agent.setPassword(passwordEncoder.encode(request.getPassword()));
        agent.setRole(RoleType.valueOf(request.getRole().toUpperCase()));

        agentRepository.save(agent);
        String token = jwtUtil.generateToken(agent);
        return new AuthResponse(agent.getId().toString(), token, agent.getRole().name());
    }

    // ── Validation mot de passe ───────────────────────────────────────
    private void validatePassword(String password) {
        if (password == null || password.length() < 8)
            throw new RuntimeException("Le mot de passe doit contenir au moins 8 caractères");
        if (!password.matches(".*[A-Z].*"))
            throw new RuntimeException("Le mot de passe doit contenir au moins une majuscule");
        if (!password.matches(".*[0-9].*"))
            throw new RuntimeException("Le mot de passe doit contenir au moins un chiffre");
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*"))
            throw new RuntimeException("Le mot de passe doit contenir au moins un caractère spécial");
    }
}