package com.example.crediSense.Service;

import java.util.List;
import java.util.UUID;

import com.example.crediSense.dto.request.DossierRequest;
import com.example.crediSense.dto.response.DossierResponse;

public interface DossierService {
     DossierResponse create(DossierRequest request);
    DossierResponse getById(UUID id);
    List<DossierResponse> getAll();
    DossierResponse update(UUID id, DossierRequest request);
    void delete(UUID id);
     List<DossierResponse> getByClientId(UUID clientId);
    
}
