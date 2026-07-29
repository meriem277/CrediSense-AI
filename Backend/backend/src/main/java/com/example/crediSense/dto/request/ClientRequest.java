package com.example.crediSense.dto.request;

import lombok.Data;

@Data
public class ClientRequest {
     private String cin;
    private String nom;
    private String prenom;
    private String email;
    private String password;
    
}
