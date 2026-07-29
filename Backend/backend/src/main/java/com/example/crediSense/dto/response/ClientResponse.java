package com.example.crediSense.dto.response;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ClientResponse {
    private UUID id;
    private String cin;
    private String nom;
    private String prenom;
    private LocalDateTime createdAt;
    private String email;
    private String photoUrl;
    private String provider;

}
