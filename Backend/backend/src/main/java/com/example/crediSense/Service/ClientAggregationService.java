package com.example.crediSense.Service;

import com.example.crediSense.dto.request.ClientAggregationRequest;
import com.example.crediSense.dto.response.ClientAggregationResponse;

import java.util.List;
import java.util.UUID;
public interface ClientAggregationService {
    

    ClientAggregationResponse create(ClientAggregationRequest request);

    ClientAggregationResponse getById(UUID id);

    ClientAggregationResponse getByCin(String cin);

    List<ClientAggregationResponse> getAll();

    ClientAggregationResponse update(UUID id, ClientAggregationRequest request);

    void delete(UUID id);

    // 🔥 utile pour ton pipeline IA
    ClientAggregationResponse upsertByCin(ClientAggregationRequest request);
}