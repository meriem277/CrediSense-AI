package com.example.crediSense.Service.impl;

import com.example.crediSense.dto.request.ClientAggregationRequest;
import com.example.crediSense.dto.response.ClientAggregationResponse;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.ClientAggregation;
import com.example.crediSense.repository.ClientAggregationRepository;
import com.example.crediSense.repository.ClientRepository;
import com.example.crediSense.Service.ClientAggregationService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ClientAggregationServiceImpl implements ClientAggregationService {

    private final ClientAggregationRepository repository;
    private final ClientRepository clientRepository;

    @Override
    public ClientAggregationResponse create(ClientAggregationRequest request) {

        ClientAggregation entity = new ClientAggregation();
        entity.setCin(request.getCin());
        entity.setAggregatedJson(request.getAggregatedJson());
        entity.setNbFichiers(request.getNbFichiers());
        entity.setLastUpdated(LocalDateTime.now());

        if (request.getClientId() != null) {
            Client client = clientRepository.findById(request.getClientId())
                    .orElseThrow(() -> new RuntimeException("Client non trouvé"));
            entity.setClient(client);
        }

        return toResponse(repository.save(entity));
    }

    @Override
    public ClientAggregationResponse getById(UUID id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Aggregation non trouvée")));
    }

    @Override
    public ClientAggregationResponse getByCin(String cin) {
        return toResponse(repository.findByCin(cin)
                .orElseThrow(() -> new RuntimeException("Aggregation non trouvée pour CIN")));
    }

    @Override
    public List<ClientAggregationResponse> getAll() {
        return repository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ClientAggregationResponse update(UUID id, ClientAggregationRequest request) {

        ClientAggregation entity = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Aggregation non trouvée"));

        entity.setCin(request.getCin());
        entity.setAggregatedJson(request.getAggregatedJson());
        entity.setNbFichiers(request.getNbFichiers());
        entity.setLastUpdated(LocalDateTime.now());

        if (request.getClientId() != null) {
            Client client = clientRepository.findById(request.getClientId())
                    .orElseThrow(() -> new RuntimeException("Client non trouvé"));
            entity.setClient(client);
        }

        return toResponse(repository.save(entity));
    }

    @Override
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    // 🔥 IMPORTANT pour ton pipeline IA
    @Override
    public ClientAggregationResponse upsertByCin(ClientAggregationRequest request) {

        ClientAggregation entity = repository.findByCin(request.getCin())
                .orElse(new ClientAggregation());

        entity.setCin(request.getCin());
        entity.setAggregatedJson(request.getAggregatedJson());
        entity.setNbFichiers(request.getNbFichiers());
        entity.setLastUpdated(LocalDateTime.now());

        if (request.getClientId() != null) {
            Client client = clientRepository.findById(request.getClientId())
                    .orElseThrow(() -> new RuntimeException("Client non trouvé"));
            entity.setClient(client);
        }

        return toResponse(repository.save(entity));
    }

    // 🔁 mapper
    private ClientAggregationResponse toResponse(ClientAggregation entity) {

        ClientAggregationResponse response = new ClientAggregationResponse();

        response.setId(entity.getId());
        response.setCin(entity.getCin());
        response.setAggregatedJson(entity.getAggregatedJson());
        response.setNbFichiers(entity.getNbFichiers());
        response.setLastUpdated(entity.getLastUpdated());

        if (entity.getClient() != null) {
            response.setClientId(entity.getClient().getId());
        }

        return response;
    }
}