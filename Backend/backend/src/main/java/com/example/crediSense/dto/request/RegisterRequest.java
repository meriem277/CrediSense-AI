package com.example.crediSense.dto.request;

import lombok.Data;

@Data

public class RegisterRequest {
    private String nom;
    private String email;
    private String password;
    private String role; // "AGENT" ou "ADMIN"
}
