package com.example.crediSense.dto.response;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

import com.example.crediSense.entity.RoleType;
@Data
public class AgentResponse {
        private UUID id;
    private String nom;
    private String email;
    private RoleType role;
    private LocalDateTime createdAt;
    
}
