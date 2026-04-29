package com.example.crediSense.dto.response;


import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class DecisionFinaleResponse {
    private UUID id;
    private Double scoreFinal;
    private String decisionFinale;
    private String justificationGlobale;
    private String explicationClient;
    private String piecesManquantes;
    private UUID dossierId;
    private LocalDateTime createdAt;

    
}
