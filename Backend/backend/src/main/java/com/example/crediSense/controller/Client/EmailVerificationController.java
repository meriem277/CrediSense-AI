package com.example.crediSense.controller.Client;

import com.example.crediSense.Service.impl.client.EmailVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/client-auth")
@CrossOrigin(origins = "http://localhost:4200")
@RequiredArgsConstructor
public class EmailVerificationController {
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(
            @RequestBody Map<String, String> body) {
        emailVerificationService.verifyEmail(body.get("token"));
        return ResponseEntity.ok(Map.of("message", "Email vérifié avec succès"));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(
            @RequestBody Map<String, String> body) {
        emailVerificationService.resendVerification(body.get("email"));
        return ResponseEntity.ok(Map.of("message", "Email de vérification renvoyé"));
    }
}
