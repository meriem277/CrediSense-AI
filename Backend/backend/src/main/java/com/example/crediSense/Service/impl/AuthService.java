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
    private final JwtUtil jwtUtil;

    public AuthResponse login(LoginRequest request) {
        Agent agent = agentRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Compte introuvable"));

        if (!passwordEncoder.matches(request.getPassword(), agent.getPassword())) {
            throw new RuntimeException("Compte introuvable");
        }

        String token = jwtUtil.generateToken(agent);
        return new AuthResponse(  agent.getId().toString(), token, agent.getEmail(), agent.getNom(), agent.getRole().name());
    }
    public AuthResponse register(RegisterRequest request) {
        if (agentRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email déjà utilisé");
        }
        Agent agent = new Agent();
        agent.setNom(request.getNom());
        agent.setEmail(request.getEmail());
        agent.setPassword(passwordEncoder.encode(request.getPassword()));
        agent.setRole(RoleType.valueOf(request.getRole().toUpperCase()));

        agentRepository.save(agent);
        String token = jwtUtil.generateToken(agent);
        return new AuthResponse(  agent.getId().toString(), token, agent.getEmail(), agent.getNom(), agent.getRole().name());
    }
}
