package com.example.crediSense.Service.impl;
import lombok.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.entity.JsonExtraction;

import com.example.crediSense.Service.JsonExtractionService;
import com.example.crediSense.dto.request.JsonExtractionRequest;
import com.example.crediSense.dto.response.JsonExtractionResponse;
import com.example.crediSense.repository.FichierRepository;
import com.example.crediSense.repository.JsonExtractionRepository;
@Service
@RequiredArgsConstructor
public class JsonExtractionServiceImpl implements JsonExtractionService {

    private final JsonExtractionRepository jsonExtractionRepository;
    private final FichierRepository fichierRepository;

    @Override
    public JsonExtractionResponse create(JsonExtractionRequest request) {
        Fichier fichier = fichierRepository.findById(request.getFichierId())
                .orElseThrow(() -> new RuntimeException("Fichier non trouvé"));
        JsonExtraction extraction = new JsonExtraction();
        extraction.setCin(request.getCin());
        extraction.setJsonData(request.getJsonData());
        extraction.setConfidenceScore(request.getConfidenceScore());
        extraction.setFichier(fichier);
        return toResponse(jsonExtractionRepository.save(extraction));
    }

    @Override
    public JsonExtractionResponse getById(UUID id) {
        return toResponse(jsonExtractionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("JsonExtraction non trouvée : " + id)));
    }

    @Override
    public List<JsonExtractionResponse> getByFichierId(UUID fichierId) {
        return jsonExtractionRepository.findByFichierId(fichierId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<JsonExtractionResponse> getByCin(String cin) {
        return jsonExtractionRepository.findByCin(cin).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<JsonExtractionResponse> getAll() {
        return jsonExtractionRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public void delete(UUID id) {
        jsonExtractionRepository.deleteById(id);
    }

    private JsonExtractionResponse toResponse(JsonExtraction extraction) {
        JsonExtractionResponse response = new JsonExtractionResponse();
        response.setId(extraction.getId());
        response.setCin(extraction.getCin());
        response.setJsonData(extraction.getJsonData());
        response.setConfidenceScore(extraction.getConfidenceScore());
        response.setFichierId(extraction.getFichier().getId());
        response.setCreatedAt(extraction.getCreatedAt());
        return response;
    }
}