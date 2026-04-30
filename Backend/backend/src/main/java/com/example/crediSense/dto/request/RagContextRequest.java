package com.example.crediSense.dto.request;

import java.util.UUID;
import lombok.Data;
@Data
public class RagContextRequest {

    private String cin;
    private String contexte;
    private String source;
    private UUID dossierId;

}
