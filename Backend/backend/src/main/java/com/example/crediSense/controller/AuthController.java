package com.example.crediSense.controller;

import com.example.crediSense.Service.impl.AuthService;
import com.example.crediSense.dto.request.LoginRequest;
import com.example.crediSense.dto.request.RegisterRequest;
import com.example.crediSense.dto.response.AuthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "http://localhost:4200")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    // Accessible seulement par ADMIN pour créer d'autres comptes
    @PostMapping("/register")
    //@PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    // Création du premier compte ADMIN (à sécuriser ensuite)
    @PostMapping("/init-admin")
    public ResponseEntity<AuthResponse> initAdmin(@RequestBody RegisterRequest request) {
        request.setRole("ADMIN");
        return ResponseEntity.ok(authService.register(request));
    }
}
