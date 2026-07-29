package com.example.crediSense.controller.Client;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import com.example.crediSense.Service.impl.client.ForgotPasswordService;


@RestController
@RequestMapping("/api/client-auth")
@CrossOrigin(origins = "http://localhost:4200")
@RequiredArgsConstructor
public class ForgetPasswordController {
    private final ForgotPasswordService forgotPasswordService;

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @RequestBody Map<String, String> body) {
        forgotPasswordService.requestReset(body.get("email"));
        return ResponseEntity.ok(Map.of("message", "Email de réinitialisation envoyé"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @RequestBody Map<String, String> body) {
        forgotPasswordService.resetPassword(body.get("token"), body.get("password"));
        return ResponseEntity.ok(Map.of("message", "Mot de passe réinitialisé avec succès"));
    }
}

