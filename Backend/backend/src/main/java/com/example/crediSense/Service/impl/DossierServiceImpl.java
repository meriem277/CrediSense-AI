package com.example.crediSense.Service.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.crediSense.Service.DossierService;
import com.example.crediSense.dto.request.DossierRequest;
import com.example.crediSense.dto.response.DossierResponse;
import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.ClientRepository;
import com.example.crediSense.repository.DossierRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DossierServiceImpl implements DossierService{
     private final DossierRepository dossierRepository;
    private final ClientRepository clientRepository;

    @Override
    public DossierResponse create(DossierRequest request) {
        Client client = clientRepository.findById(request.getClientId())
                .orElseThrow(() -> new RuntimeException("Client non trouvé"));
        Dossier dossier = new Dossier();
        dossier.setTypeCredit(request.getTypeCredit());
        dossier.setStatut(request.getStatut());
        dossier.setClient(client);
        return toResponse(dossierRepository.save(dossier));
    }

    @Override
    public DossierResponse getById(UUID id) {
        return toResponse(dossierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dossier non trouvé : " + id)));
    }

    @Override
    public List<DossierResponse> getByClientId(UUID clientId) {
        return dossierRepository.findByClientId(clientId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
    
     @Override
    public List<DossierResponse> getAll() {
        return dossierRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public DossierResponse update(UUID id, DossierRequest request) {
        Dossier dossier = dossierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Dossier non trouvé : " + id));
        dossier.setTypeCredit(request.getTypeCredit());
        dossier.setStatut(request.getStatut());
        return toResponse(dossierRepository.save(dossier));
    }

    @Override
    public void delete(UUID id) {
        dossierRepository.deleteById(id);
    }

    private DossierResponse toResponse(Dossier dossier) {
        DossierResponse response = new DossierResponse();
        response.setId(dossier.getId());
        response.setTypeCredit(dossier.getTypeCredit());
        response.setStatut(dossier.getStatut());
        response.setClientId(dossier.getClient().getId());
        response.setCreatedAt(dossier.getCreatedAt());
        return response;
    }
}
