package com.example.crediSense.dto.response;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class DossierResponse {
       private UUID id;
    private String typeCredit;
    private String statut;
    private UUID clientId;
    private LocalDateTime createdAt;
    
}
