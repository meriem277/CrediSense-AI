package com.example.crediSense.dto.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class AgentAnalysisResponse {
    private UUID id;
    private String typeAgent;
    private Double score;
    private Double solvabilite;
    private Double revenus;
    private Double endettement;
    private Double historique;
    private String decision;
    private String justification;
    private String piecesManquantes;
    private UUID dossierId;
    private LocalDateTime createdAt;
}