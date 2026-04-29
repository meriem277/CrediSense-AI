package com.example.crediSense.dto.request;

import lombok.Data;
import java.util.UUID;


@Data
public class DecisionFinaleRequest {
    private Double scoreFinal;
    private String decisionFinale;
    private String justificationGlobale;
    private String explicationClient;
    private String piecesManquantes;
    private UUID dossierId;
    private UUID clientAggregationId;

}
