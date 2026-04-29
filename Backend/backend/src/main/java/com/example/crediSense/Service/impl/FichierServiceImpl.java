package com.example.crediSense.Service.impl;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.crediSense.Service.FichierService;
import com.example.crediSense.dto.request.FichierRequest;
import com.example.crediSense.dto.response.FichierResponse;
import com.example.crediSense.entity.Agent;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.FichierRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FichierServiceImpl  implements FichierService {
      private final FichierRepository fichierRepository;
    private final AgentRepository agentRepository;
  @Override
    public FichierResponse create(FichierRequest request) {
        Agent agent = agentRepository.findById(request.getAgentId())
                .orElseThrow(() -> new RuntimeException("Agent non trouvé"));
        Fichier fichier = new Fichier();
        fichier.setCin(request.getCin());
        fichier.setNomOriginal(request.getNomOriginal());
        fichier.setTypeOriginal(request.getTypeOriginal());
        fichier.setCheminPdf(request.getCheminPdf());
        fichier.setAgent(agent);
        return toResponse(fichierRepository.save(fichier));
    }
 @Override
    public FichierResponse getById(UUID id) {
        return toResponse(fichierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Fichier non trouvé : " + id)));
    }

    @Override
    public List<FichierResponse> getByAgentId(UUID agentId) {
        return fichierRepository.findByAgentId(agentId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<FichierResponse> getByCin(String cin) {
        return fichierRepository.findByCin(cin).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
    @Override
    public List<FichierResponse> getAll() {
        return fichierRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public void delete(UUID id) {
        fichierRepository.deleteById(id);
    }

    private FichierResponse toResponse(Fichier fichier) {
        FichierResponse response = new FichierResponse();
        response.setId(fichier.getId());
        response.setCin(fichier.getCin());
        response.setNomOriginal(fichier.getNomOriginal());
        response.setTypeOriginal(fichier.getTypeOriginal());
        response.setCheminPdf(fichier.getCheminPdf());
        response.setAgentId(fichier.getAgent().getId());
        response.setCreatedAt(fichier.getCreatedAt());
        return response;
    }
}
