package com.example.crediSense.Service;

import java.util.List;
import java.util.UUID;

import com.example.crediSense.dto.request.JsonExtractionRequest;
import com.example.crediSense.dto.response.JsonExtractionResponse;

public interface JsonExtractionService {
    JsonExtractionResponse create(JsonExtractionRequest request);
    JsonExtractionResponse getById(UUID id);
    List<JsonExtractionResponse> getByFichierId(UUID fichierId);
    List<JsonExtractionResponse> getByCin(String cin);
    List<JsonExtractionResponse> getAll();
    void delete(UUID id);
}