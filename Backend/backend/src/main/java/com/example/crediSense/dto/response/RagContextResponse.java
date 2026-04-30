package com.example.crediSense.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class RagContextResponse {

    private UUID id;
    private String cin;
    private String contexte;
    private String source;
    private LocalDateTime createdAt;

    // utile pour front
    private UUID dossierId;


    
}
