package com.example.crediSense.dto.response;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class FichierResponse {
    
        private UUID id;
    private String cin;
    private String nomOriginal;
    private String typeOriginal;
    private String cheminPdf;
    private UUID agentId;
    private LocalDateTime createdAt;

}
