package com.example.crediSense.Service.impl.client;

import com.example.crediSense.entity.Client;
import com.example.crediSense.repository.ClientRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForgotPasswordService {

    private final ClientRepository clientRepository;
    private final JavaMailSender   mailSender;
    private final PasswordEncoder  passwordEncoder;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    // ✅ Stockage temporaire des tokens (en prod → table DB)
    private final Map<String, TokenEntry> tokenStore = new ConcurrentHashMap<>();

    // ── Demande reset ─────────────────────────────────────────────────
    public void requestReset(String email) {
        Client client = clientRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Aucun compte trouvé avec cet email"));

        String token = UUID.randomUUID().toString();
        tokenStore.put(token, new TokenEntry(email, LocalDateTime.now().plusMinutes(15)));

        String resetLink = frontendUrl + "/client/reset-password?token=" + token;

        try {
            sendResetEmail(email, client.getNom(), resetLink);
            log.info("Email de reset envoyé à : {}", email);
        } catch (Exception e) {
            log.error("Échec envoi email de reset à {} : {}", email, e.getMessage());
            throw new RuntimeException("Impossible d'envoyer l'email de réinitialisation");
        }
    }

    private void sendResetEmail(String to, String nom, String resetLink) throws Exception {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

        helper.setTo(to);
        helper.setSubject("Réinitialisation de votre mot de passe — CrediSense");

        String html = """
            <div style="font-family: Arial, sans-serif; max-width: 480px; margin: auto; padding: 32px; background: #ffffff;">
              <h2 style="color: #111111; margin-bottom: 4px;">Bonjour %s,</h2>
              <p style="color: #666666; font-size: 14px; line-height: 1.5;">
                Vous avez demandé la réinitialisation de votre mot de passe CrediSense.
              </p>
              <div style="text-align: center; margin: 32px 0;">
                <a href="%s"
                   style="background: linear-gradient(135deg, #E8302A, #F47920);
                          color: #ffffff; text-decoration: none;
                          padding: 14px 32px; border-radius: 999px;
                          font-weight: 700; font-size: 14px; display: inline-block;">
                  Réinitialiser mon mot de passe →
                </a>
              </div>
              <p style="color: #999999; font-size: 12.5px; line-height: 1.5;">
                Ce lien expire dans <strong>15 minutes</strong>.<br>
                Si vous n'avez pas fait cette demande, ignorez simplement cet email.
              </p>
              <hr style="border: none; border-top: 1px solid #eeeeee; margin: 24px 0;">
              <p style="color: #aaaaaa; font-size: 12px; text-align: center;">
                Attijari Bank — CrediSense
              </p>
            </div>
            """.formatted(nom, resetLink);

        helper.setText(html, true);
        mailSender.send(mimeMessage);
    }

    // ── Reset mot de passe ────────────────────────────────────────────
    public void resetPassword(String token, String newPassword) {
        TokenEntry entry = tokenStore.get(token);

        if (entry == null || entry.expiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Lien expiré ou invalide");
        }

        Client client = clientRepository.findByEmail(entry.email())
                .orElseThrow(() -> new RuntimeException("Compte introuvable"));

        validatePassword(newPassword);

        client.setPassword(passwordEncoder.encode(newPassword));
        clientRepository.save(client);
        tokenStore.remove(token);
        log.info("Mot de passe réinitialisé pour : {}", entry.email());
    }

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

    // ── Record interne ────────────────────────────────────────────────
    private record TokenEntry(String email, LocalDateTime expiry) {}
}