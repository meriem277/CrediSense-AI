package com.example.crediSense.dto.request;

import lombok.Data;
import java.util.UUID;

@Data
public class FichierRequest {  
     private String cin;
    private String nomOriginal;
    private String typeOriginal;
    private String cheminPdf;
    private UUID agentId;
    private UUID dossierId;


}
