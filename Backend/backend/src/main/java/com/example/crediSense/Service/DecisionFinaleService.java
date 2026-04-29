package com.example.crediSense.Service;

import java.util.List;
import java.util.UUID;

import com.example.crediSense.dto.request.DecisionFinaleRequest;
import com.example.crediSense.dto.response.DecisionFinaleResponse;

public interface DecisionFinaleService {
      DecisionFinaleResponse create(DecisionFinaleRequest request);
    DecisionFinaleResponse getById(UUID id);
    DecisionFinaleResponse getByDossierId(UUID dossierId);
    List<DecisionFinaleResponse> getAll();
    DecisionFinaleResponse update(UUID id, DecisionFinaleRequest request);
    void delete(UUID id);
    
}
