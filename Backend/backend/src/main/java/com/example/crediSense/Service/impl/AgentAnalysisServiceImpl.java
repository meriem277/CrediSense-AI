package com.example.crediSense.Service.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.crediSense.Service.AgentAnalysisService;
import com.example.crediSense.dto.request.AgentAnalysisRequest;
import com.example.crediSense.dto.response.AgentAnalysisResponse;
import com.example.crediSense.entity.AgentAnalysis;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.AgentAnalysisRepository;
import com.example.crediSense.repository.DossierRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AgentAnalysisServiceImpl  implements AgentAnalysisService {

    private final AgentAnalysisRepository agentAnalysisRepository;
    private final DossierRepository dossierRepository;

    @Override
    public AgentAnalysisResponse create(AgentAnalysisRequest request) {
        Dossier dossier = dossierRepository.findById(request.getDossierId())
                .orElseThrow(() -> new RuntimeException("Dossier non trouvé"));
        AgentAnalysis analysis = new AgentAnalysis();
        analysis.setTypeAgent(request.getTypeAgent());
        analysis.setScore(request.getScore());
        analysis.setSolvabilite(request.getSolvabilite());
        analysis.setRevenus(request.getRevenus());
        analysis.setEndettement(request.getEndettement());
        analysis.setHistorique(request.getHistorique());
        analysis.setDecision(request.getDecision());
        analysis.setJustification(request.getJustification());
        analysis.setPiecesManquantes(request.getPiecesManquantes());
        analysis.setDossier(dossier);
        return toResponse(agentAnalysisRepository.save(analysis));
    }
      @Override
    public AgentAnalysisResponse getById(UUID id) {
        return toResponse(agentAnalysisRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("AgentAnalysis non trouvée : " + id)));
    }

    @Override
    public AgentAnalysisResponse getByDossierId(UUID dossierId) {
        return toResponse(agentAnalysisRepository.findByDossierId(dossierId)
                .orElseThrow(() -> new RuntimeException("Aucune analyse pour le dossier : " + dossierId)));
    }

    @Override
    public List<AgentAnalysisResponse> getAll() {
        return agentAnalysisRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
     @Override
    public AgentAnalysisResponse update(UUID id, AgentAnalysisRequest request) {
        AgentAnalysis analysis = agentAnalysisRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("AgentAnalysis non trouvée : " + id));
        analysis.setTypeAgent(request.getTypeAgent());
        analysis.setScore(request.getScore());
        analysis.setSolvabilite(request.getSolvabilite());
        analysis.setRevenus(request.getRevenus());
        analysis.setEndettement(request.getEndettement());
        analysis.setHistorique(request.getHistorique());
        analysis.setDecision(request.getDecision());
        analysis.setJustification(request.getJustification());
        analysis.setPiecesManquantes(request.getPiecesManquantes());
        return toResponse(agentAnalysisRepository.save(analysis));
    }
     @Override
    public void delete(UUID id) {
        agentAnalysisRepository.deleteById(id);
    }

    private AgentAnalysisResponse toResponse(AgentAnalysis analysis) {
        AgentAnalysisResponse response = new AgentAnalysisResponse();
        response.setId(analysis.getId());
        response.setTypeAgent(analysis.getTypeAgent());
        response.setScore(analysis.getScore());
        response.setSolvabilite(analysis.getSolvabilite());
        response.setRevenus(analysis.getRevenus());
        response.setEndettement(analysis.getEndettement());
        response.setHistorique(analysis.getHistorique());
        response.setDecision(analysis.getDecision());
        response.setJustification(analysis.getJustification());
        response.setPiecesManquantes(analysis.getPiecesManquantes());
        response.setDossierId(analysis.getDossier().getId());
        response.setCreatedAt(analysis.getCreatedAt());
        return response;
    }
    
}
