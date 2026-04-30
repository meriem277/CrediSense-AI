package com.example.crediSense.dto.request;

import lombok.Data;

import java.util.UUID;

@Data
public class ClientAggregationRequest {

    private String cin;
    private String aggregatedJson;
    private Integer nbFichiers;

    // liaison client
    private UUID clientId;
}