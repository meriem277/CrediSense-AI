package com.example.crediSense.dto.request;
import lombok.Data;
import java.util.UUID;

@Data
public class DossierRequest {
    private String typeCredit;
    private String statut;
    private UUID clientId;
    
}
