package com.example.crediSense.Service.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.crediSense.Service.DecisionFinaleService;
import com.example.crediSense.dto.request.DecisionFinaleRequest;
import com.example.crediSense.dto.response.DecisionFinaleResponse;
import com.example.crediSense.entity.ClientAggregation;
import com.example.crediSense.entity.DecisionFinale;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.repository.ClientAggregationRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import com.example.crediSense.repository.DossierRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DecisionFinaleServiceImpl implements DecisionFinaleService {

    private final DecisionFinaleRepository decisionFinaleRepository;
    private final DossierRepository dossierRepository;
    private final ClientAggregationRepository clientAggregationRepository;

    @Override
    public DecisionFinaleResponse create(DecisionFinaleRequest request) {
        Dossier dossier = dossierRepository.findById(request.getDossierId())
                .orElseThrow(() -> new RuntimeException("Dossier non trouvé"));
        ClientAggregation aggregation = clientAggregationRepository
                .findById(request.getClientAggregationId())
                .orElseThrow(() -> new RuntimeException("ClientAggregation non trouvée"));
        DecisionFinale decision = new DecisionFinale();
        decision.setScoreFinal(request.getScoreFinal());
        decision.setDecisionFinale(request.getDecisionFinale());
        decision.setJustificationGlobale(request.getJustificationGlobale());
        decision.setExplicationClient(request.getExplicationClient());
        decision.setPiecesManquantes(request.getPiecesManquantes());
        decision.setDossier(dossier);
        decision.setClientAggregation(aggregation);
        return toResponse(decisionFinaleRepository.save(decision));
    }

    @Override
    public DecisionFinaleResponse getById(UUID id) {
        return toResponse(decisionFinaleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("DecisionFinale non trouvée : " + id)));
    }

    @Override
    public DecisionFinaleResponse getByDossierId(UUID dossierId) {
        return toResponse(decisionFinaleRepository.findByDossierId(dossierId)
                .orElseThrow(() -> new RuntimeException("Aucune décision pour le dossier : " + dossierId)));
    }

    @Override
    public List<DecisionFinaleResponse> getAll() {
        return decisionFinaleRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public DecisionFinaleResponse update(UUID id, DecisionFinaleRequest request) {
        DecisionFinale decision = decisionFinaleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("DecisionFinale non trouvée : " + id));
        decision.setScoreFinal(request.getScoreFinal());
        decision.setDecisionFinale(request.getDecisionFinale());
        decision.setJustificationGlobale(request.getJustificationGlobale());
        decision.setExplicationClient(request.getExplicationClient());
        decision.setPiecesManquantes(request.getPiecesManquantes());
        return toResponse(decisionFinaleRepository.save(decision));
    }

    @Override
    public void delete(UUID id) {
        decisionFinaleRepository.deleteById(id);
    }

    private DecisionFinaleResponse toResponse(DecisionFinale decision) {
        DecisionFinaleResponse response = new DecisionFinaleResponse();
        response.setId(decision.getId());
        response.setScoreFinal(decision.getScoreFinal());
        response.setDecisionFinale(decision.getDecisionFinale());
        response.setJustificationGlobale(decision.getJustificationGlobale());
        response.setExplicationClient(decision.getExplicationClient());
        response.setPiecesManquantes(decision.getPiecesManquantes());
        response.setDossierId(decision.getDossier().getId());
        response.setCreatedAt(decision.getCreatedAt());
        return response;
    }
}