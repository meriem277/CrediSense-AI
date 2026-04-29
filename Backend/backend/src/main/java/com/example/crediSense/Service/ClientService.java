package com.example.crediSense.Service;

import java.util.List;
import java.util.UUID;

import com.example.crediSense.dto.request.ClientRequest;
import com.example.crediSense.dto.response.ClientResponse;

public interface ClientService {
    ClientResponse create(ClientRequest request);
    ClientResponse getById(UUID id);
    ClientResponse getByCin(String cin);
    List<ClientResponse> getAll();
    ClientResponse update(UUID id, ClientRequest request);
    void delete(UUID id);
}