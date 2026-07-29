package com.example.crediSense.Service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.example.crediSense.entity.Agent;
import com.example.crediSense.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentForgotPasswordService {

    private final AgentRepository agentRepository;
    private final JavaMailSender  mailSender;
    private final PasswordEncoder passwordEncoder;

    // ✅ Stockage temporaire des tokens
    private final Map<String, TokenEntry> tokenStore = new ConcurrentHashMap<>();

    // ── Demande reset ─────────────────────────────────────────────────
    public void requestReset(String email) {
        Agent agent = agentRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Aucun compte trouvé avec cet email"));

        String token     = UUID.randomUUID().toString();
        LocalDateTime expiry = LocalDateTime.now().plusMinutes(15);
        tokenStore.put(token, new TokenEntry(email, expiry));

        String resetLink = "http://localhost:4200/reset-password?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("Réinitialisation de votre mot de passe — CrediSense");
        message.setText(
                "Bonjour " + agent.getNom() + ",\n\n" +
                        "Cliquez sur le lien ci-dessous pour réinitialiser votre mot de passe :\n\n" +
                        resetLink + "\n\n" +
                        "Ce lien expire dans 15 minutes.\n\n" +
                        "Si vous n'avez pas fait cette demande, ignorez cet email.\n\n" +
                        "Attijariwafa Bank — CrediSense"
        );
        mailSender.send(message);
        log.info("Email reset envoyé à l'agent : {}", email);
    }

    // ── Reset mot de passe ────────────────────────────────────────────
    public void resetPassword(String token, String newPassword) {
        TokenEntry entry = tokenStore.get(token);

        if (entry == null || entry.expiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Lien expiré ou invalide");
        }

        Agent agent = agentRepository.findByEmail(entry.email())
                .orElseThrow(() -> new RuntimeException("Compte introuvable"));

        validatePassword(newPassword);

        agent.setPassword(passwordEncoder.encode(newPassword));
        agentRepository.save(agent);
        tokenStore.remove(token);
        log.info("Mot de passe réinitialisé pour l'agent : {}", entry.email());
    }

    // ── Validation ────────────────────────────────────────────────────
    private void validatePassword(String password) {
        if (password == null || password.length() < 8)
            throw new RuntimeException("Au moins 8 caractères requis");
        if (!password.matches(".*[A-Z].*"))
            throw new RuntimeException("Au moins une majuscule requise");
        if (!password.matches(".*[0-9].*"))
            throw new RuntimeException("Au moins un chiffre requis");
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*"))
            throw new RuntimeException("Au moins un caractère spécial requis");
    }

    // ── Record interne ────────────────────────────────────────────────
    private record TokenEntry(String email, LocalDateTime expiry) {}
}

