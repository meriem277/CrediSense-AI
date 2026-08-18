package com.example.crediSense.Service.impl.client;
import com.example.crediSense.entity.Client;
import com.example.crediSense.jwt.JwtUtil;
import com.example.crediSense.repository.ClientRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClientAuthService {

    private final ClientRepository clientRepository;
    private final PasswordEncoder  passwordEncoder;
    private final JwtUtil          jwtUtil;
    private final RestTemplate     restTemplate;
    private final ObjectMapper     objectMapper;

    // ─── Inscription email/password ───────────────────────────────────────────

    public Map<String, Object> register(String email, String password,
                                        String nom, String prenom) {
        if (clientRepository.existsByEmail(email)) {
            throw new RuntimeException("Email déjà utilisé");
        }

        // ✅ Validation mot de passe côté backend
        validatePassword(password);

        Client client = new Client();
        client.setEmail(email);
        client.setPassword(passwordEncoder.encode(password));
        client.setNom(nom);
        client.setPrenom(prenom);
        client.setProvider("LOCAL");

        Client saved = clientRepository.save(client);
        String token = jwtUtil.generateClientToken(saved);
        return buildResponse(saved, token);
    }

    // ✅ Méthode de validation
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

    // ─── Connexion email/password ─────────────────────────────────────────────

    public Map<String, Object> login(String email, String password) {
        Client client = clientRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Compte introuvable"));

        if (client.getPassword() == null) {
            throw new RuntimeException("Ce compte utilise la connexion Google");
        }

        if (!passwordEncoder.matches(password, client.getPassword())) {
            throw new RuntimeException("Mot de passe incorrect");
        }

        String token = jwtUtil.generateClientToken(client);
        log.info("Client connecté : {}", email);
        return buildResponse(client, token);
    }

    // ─── Connexion Google OAuth2 ──────────────────────────────────────────────

    public Map<String, Object> loginWithGoogle(String googleToken) {
        String verifyUrl = "https://oauth2.googleapis.com/tokeninfo?id_token=" + googleToken;

        try {
            String   response = restTemplate.getForObject(verifyUrl, String.class);
            JsonNode node     = objectMapper.readTree(response);

            String googleId = node.path("sub").asText();
            String email    = node.path("email").asText();
            String nom      = node.path("family_name").asText();
            String prenom   = node.path("given_name").asText();
            String photoUrl = node.path("picture").asText();

            // Chercher par googleId d'abord, puis par email
            Client client = clientRepository.findByGoogleId(googleId)
                    .orElseGet(() -> clientRepository.findByEmail(email)
                            .orElseGet(() -> {
                                Client newClient = new Client();
                                newClient.setEmail(email);
                                newClient.setNom(nom);
                                newClient.setPrenom(prenom);
                                newClient.setGoogleId(googleId);
                                newClient.setPhotoUrl(photoUrl);
                                newClient.setProvider("GOOGLE");
                                return clientRepository.save(newClient);
                            }));

            // Mettre à jour googleId si compte local existait
            if (client.getGoogleId() == null) {
                client.setGoogleId(googleId);
                client.setPhotoUrl(photoUrl);
                clientRepository.save(client);
            }

            String token = jwtUtil.generateClientToken(client);
            log.info("Client connecté via Google : {}", email);
            return buildResponse(client, token);

        } catch (Exception e) {
            log.error("Erreur vérification token Google : {}", e.getMessage());
            throw new RuntimeException("Token Google invalide");
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildResponse(Client client, String token) {
        return Map.of(
                "token",    token,
                "id",       client.getId().toString(),
                "email",    client.getEmail() != null    ? client.getEmail()    : "",
                "cin",      client.getCin() != null       ? client.getCin()      : "",
                "nom",      client.getNom() != null      ? client.getNom()      : "",
                "prenom",   client.getPrenom() != null   ? client.getPrenom()   : "",
                "photoUrl", client.getPhotoUrl() != null ? client.getPhotoUrl() : "",
                "provider", client.getProvider() != null ? client.getProvider() : "LOCAL",
                "role",     "CLIENT"
        );
    }
}
