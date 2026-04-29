package com.example.crediSense.Service.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.crediSense.Service.ClientService;
import com.example.crediSense.dto.request.ClientRequest;
import com.example.crediSense.dto.response.ClientResponse;
import com.example.crediSense.entity.Client;
import com.example.crediSense.repository.ClientRepository;

import lombok.RequiredArgsConstructor;

 @Service
@RequiredArgsConstructor
public class ClientServiceImpl implements ClientService {

    private final ClientRepository clientRepository;

    @Override
    public ClientResponse create(ClientRequest request) {
        Client client = new Client();
        client.setCin(request.getCin());
        client.setNom(request.getNom());
        client.setPrenom(request.getPrenom());
        return toResponse(clientRepository.save(client));
    }

    @Override
    public ClientResponse getById(UUID id) {
        return toResponse(clientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client non trouvé : " + id)));
    }

    @Override
    public ClientResponse getByCin(String cin) {
        return toResponse(clientRepository.findByCin(cin)
                .orElseThrow(() -> new RuntimeException("Client non trouvé avec CIN : " + cin)));
    }

    @Override
    public List<ClientResponse> getAll() {
        return clientRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ClientResponse update(UUID id, ClientRequest request) {
        Client client = clientRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Client non trouvé : " + id));
        client.setCin(request.getCin());
        client.setNom(request.getNom());
        client.setPrenom(request.getPrenom());
        return toResponse(clientRepository.save(client));
    }

    @Override
    public void delete(UUID id) {
        clientRepository.deleteById(id);
    }

    private ClientResponse toResponse(Client client) {
        ClientResponse response = new ClientResponse();
        response.setId(client.getId());
        response.setCin(client.getCin());
        response.setNom(client.getNom());
        response.setPrenom(client.getPrenom());
        response.setCreatedAt(client.getCreatedAt());
        return response;
    }}
    