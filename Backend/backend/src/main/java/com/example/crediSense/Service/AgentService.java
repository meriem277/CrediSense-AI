package com.example.crediSense.Service;

import java.util.List;
import java.util.UUID;

import com.example.crediSense.dto.request.AgentRequest;
import com.example.crediSense.dto.response.AgentResponse;
public interface AgentService {
     AgentResponse create(AgentRequest request);
    AgentResponse getById(UUID id);
    List<AgentResponse> getAll();
    AgentResponse update(UUID id, AgentRequest request);
    void delete(UUID id);
}
