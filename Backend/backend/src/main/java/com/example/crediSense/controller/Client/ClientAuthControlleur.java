package com.example.crediSense.controller.Client;
import com.example.crediSense.Service.impl.client.ClientAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/client-auth")
@CrossOrigin(origins = "http://localhost:4200")
@RequiredArgsConstructor
public class ClientAuthControlleur {
    private final ClientAuthService clientAuthService;

    // ── Inscription email/password ─────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(clientAuthService.register(
                request.email(),
                request.password(),
                request.nom(),
                request.prenom()
        ));
    }

    // ── Connexion email/password ───────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody LoginRequest request) {
        return ResponseEntity.ok(clientAuthService.login(
                request.email(),
                request.password()
        ));
    }

    // ── Connexion Google OAuth2 ────────────────────────────────────────────
    @PostMapping("/google")
    public ResponseEntity<Map<String, Object>> loginWithGoogle(
            @RequestBody GoogleRequest request) {
        return ResponseEntity.ok(clientAuthService.loginWithGoogle(
                request.googleToken()
        ));
    }

    // ── Records ───────────────────────────────────────────────────────────
    public record RegisterRequest(
            String email,
            String password,
            String nom,
            String prenom
    ) {}

    public record LoginRequest(
            String email,
            String password
    ) {}

    public record GoogleRequest(
            String googleToken
    ) {}
}
