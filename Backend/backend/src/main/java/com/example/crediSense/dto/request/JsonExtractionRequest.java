package com.example.crediSense.dto.request;
import java.util.UUID;

import lombok.Data;

@Data
public class JsonExtractionRequest {
      private String cin;
    private String jsonData;
    private Double confidenceScore;
    private UUID fichierId;
    
}
