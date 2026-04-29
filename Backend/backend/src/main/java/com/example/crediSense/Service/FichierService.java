package com.example.crediSense.Service;

import java.util.List;
import java.util.UUID;

import com.example.crediSense.dto.request.FichierRequest;
import com.example.crediSense.dto.response.FichierResponse;

public interface FichierService {
     FichierResponse create(FichierRequest request);
    FichierResponse getById(UUID id);
    List<FichierResponse> getByAgentId(UUID agentId);
    List<FichierResponse> getByCin(String cin);
    List<FichierResponse> getAll();
    void delete(UUID id);
}
