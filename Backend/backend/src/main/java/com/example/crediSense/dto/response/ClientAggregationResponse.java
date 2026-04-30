package com.example.crediSense.dto.response;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class ClientAggregationResponse {
        private UUID id;
    private String cin;
    private String aggregatedJson;
    private Integer nbFichiers;
    private LocalDateTime lastUpdated;

    private UUID clientId;
}