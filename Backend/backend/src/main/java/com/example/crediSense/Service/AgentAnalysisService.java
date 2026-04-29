package com.example.crediSense.Service;

import java.util.List;
import java.util.UUID;

import com.example.crediSense.dto.request.AgentAnalysisRequest;
import com.example.crediSense.dto.response.AgentAnalysisResponse;

public interface  AgentAnalysisService {
      AgentAnalysisResponse create(AgentAnalysisRequest request);
    AgentAnalysisResponse getById(UUID id);
    AgentAnalysisResponse getByDossierId(UUID dossierId);
    List<AgentAnalysisResponse> getAll();
    AgentAnalysisResponse update(UUID id, AgentAnalysisRequest request);
    void delete(UUID id);
    
}
