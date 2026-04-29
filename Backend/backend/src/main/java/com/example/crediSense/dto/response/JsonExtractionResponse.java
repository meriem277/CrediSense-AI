package com.example.crediSense.dto.response;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Data;

@Data
public class JsonExtractionResponse {
       private UUID id;
    private String cin;
    private String jsonData;
    private Double confidenceScore;
    private UUID fichierId;
    private LocalDateTime createdAt;
    
}
