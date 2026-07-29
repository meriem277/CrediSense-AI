package com.example.crediSense.Service.impl.client;

import com.example.crediSense.entity.Client;
import com.example.crediSense.repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForgotPasswordService {


    private final ClientRepository clientRepository;
    private final JavaMailSender mailSender;

    // ✅ Stockage temporaire des tokens (en prod → table DB)
    private final Map<String, TokenEntry> tokenStore = new ConcurrentHashMap<>();

    // ── Demande reset ─────────────────────────────────────────────────
    public void requestReset(String email) {
        Client client = clientRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Aucun compte trouvé avec cet email"));

        String token = UUID.randomUUID().toString();
        tokenStore.put(token, new TokenEntry(email, LocalDateTime.now().plusMinutes(15)));

        String resetLink = "http://localhost:4200/client/reset-password?token=" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("Réinitialisation de votre mot de passe — CrediSense");
        message.setText(
                "Bonjour " + client.getNom() + ",\n\n" +
                        "Cliquez sur le lien ci-dessous pour réinitialiser votre mot de passe :\n\n" +
                        resetLink + "\n\n" +
                        "Ce lien expire dans 15 minutes.\n\n" +
                        "Si vous n'avez pas fait cette demande, ignorez cet email.\n\n" +
                        "Attijariwafa Bank — CrediSense"
        );
        mailSender.send(message);
        log.info("Email de reset envoyé à : {}", email);
    }

    // ── Reset mot de passe ────────────────────────────────────────────
    public void resetPassword(String token, String newPassword) {
        TokenEntry entry = tokenStore.get(token);

        if (entry == null || entry.expiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Lien expiré ou invalide");
        }

        Client client = clientRepository.findByEmail(entry.email())
                .orElseThrow(() -> new RuntimeException("Compte introuvable"));

        // ✅ Validation du nouveau mot de passe
        validatePassword(newPassword);

        client.setPassword(new org.springframework.security.crypto.bcrypt
                .BCryptPasswordEncoder().encode(newPassword));
        clientRepository.save(client);
        tokenStore.remove(token);
        log.info("Mot de passe réinitialisé pour : {}", entry.email());
    }

    private void validatePassword(String password) {
        if (password.length() < 8)
            throw new RuntimeException("Au moins 8 caractères requis");
        if (!password.matches(".*[A-Z].*"))
            throw new RuntimeException("Au moins une majuscule requise");
        if (!password.matches(".*[0-9].*"))
            throw new RuntimeException("Au moins un chiffre requis");
    }

    // ── Record interne ────────────────────────────────────────────────
    private record TokenEntry(String email, LocalDateTime expiry) {}
}
