package com.example.crediSense.dto.request;
import lombok.Data;
import java.util.UUID;
@Data
public class AgentAnalysisRequest {
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

    
}
