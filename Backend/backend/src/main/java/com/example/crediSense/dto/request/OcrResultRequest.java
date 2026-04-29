package com.example.crediSense.dto.request;
import java.util.UUID;

import lombok.Data;

@Data
public class OcrResultRequest {
     private String texteBrut;
    private String texteNettoye;
    private String statut;
    private UUID fichierId;
    
}
