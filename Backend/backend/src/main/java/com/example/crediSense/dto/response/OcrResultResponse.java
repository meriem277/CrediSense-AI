package com.example.crediSense.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.Data;

@Data
public class OcrResultResponse {
     private UUID id;
    private String texteBrut;
    private String texteNettoye;
    private String statut;
    private UUID fichierId;
    private LocalDateTime createdAt;
    
}
