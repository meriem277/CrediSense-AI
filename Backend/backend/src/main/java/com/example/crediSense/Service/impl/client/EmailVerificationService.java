package com.example.crediSense.Service.impl.client;

import com.example.crediSense.entity.Client;
import com.example.crediSense.repository.ClientRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final ClientRepository clientRepository;
    private final JavaMailSender   mailSender;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    // ✅ Stockage temporaire des tokens (en prod → table DB)
    private final Map<String, TokenEntry> tokenStore = new ConcurrentHashMap<>();

    // ── Envoi du lien de vérification ──────────────────────────────────
    public void sendVerificationEmail(Client client) {
        String token = UUID.randomUUID().toString();
        tokenStore.put(token, new TokenEntry(client.getEmail(), LocalDateTime.now().plusHours(24)));

        String verifyLink = frontendUrl + "/client/verify-email?token=" + token;

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

            helper.setTo(client.getEmail());
            helper.setSubject("Confirmez votre adresse email — CrediSense");

            String html = """
                <div style="font-family: Arial, sans-serif; max-width: 480px; margin: auto; padding: 32px; background: #ffffff;">
                  <h2 style="color: #111111; margin-bottom: 4px;">Bonjour %s,</h2>
                  <p style="color: #666666; font-size: 14px; line-height: 1.5;">
                    Merci de vous être inscrit sur CrediSense. Confirmez votre adresse email pour activer votre compte.
                  </p>
                  <div style="text-align: center; margin: 32px 0;">
                    <a href="%s"
                       style="background: linear-gradient(135deg, #E8302A, #F47920);
                              color: #ffffff; text-decoration: none;
                              padding: 14px 32px; border-radius: 999px;
                              font-weight: 700; font-size: 14px; display: inline-block;">
                      Confirmer mon email →
                    </a>
                  </div>
                  <p style="color: #999999; font-size: 12.5px; line-height: 1.5;">
                    Ce lien expire dans <strong>24 heures</strong>.<br>
                    Si vous n'êtes pas à l'origine de cette inscription, ignorez cet email.
                  </p>
                  <hr style="border: none; border-top: 1px solid #eeeeee; margin: 24px 0;">
                  <p style="color: #aaaaaa; font-size: 12px; text-align: center;">
                    Attijari Bank — CrediSense
                  </p>
                </div>
                """.formatted(client.getNom(), verifyLink);

            helper.setText(html, true);
            mailSender.send(mimeMessage);
            log.info("Email de vérification envoyé à : {}", client.getEmail());
        } catch (Exception e) {
            log.error("Échec envoi email de vérification à {} : {}", client.getEmail(), e.getMessage());
            throw new RuntimeException("Impossible d'envoyer l'email de vérification");
        }
    }

    // ── Vérification du token ───────────────────────────────────────────
    public void verifyEmail(String token) {
        TokenEntry entry = tokenStore.get(token);

        if (entry == null || entry.expiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Lien de vérification expiré ou invalide");
        }

        Client client = clientRepository.findByEmail(entry.email())
                .orElseThrow(() -> new RuntimeException("Compte introuvable"));

        client.setEmailVerified(true);
        clientRepository.save(client);
        tokenStore.remove(token);
        log.info("Email vérifié pour : {}", entry.email());
    }

    // ── Renvoi du lien (bonus, pratique côté UX) ────────────────────────
    public void resendVerification(String email) {
        Client client = clientRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Aucun compte trouvé avec cet email"));

        if (client.isEmailVerified()) {
            throw new RuntimeException("Cet email est déjà vérifié");
        }

        sendVerificationEmail(client);
    }

    private record TokenEntry(String email, LocalDateTime expiry) {}
}