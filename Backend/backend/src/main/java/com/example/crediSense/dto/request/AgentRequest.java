package com.example.crediSense.dto.request;

import com.example.crediSense.entity.RoleType;

import lombok.Data;

@Data
public class AgentRequest {
     private String nom;
    private String email;
    private RoleType role;
    
}
