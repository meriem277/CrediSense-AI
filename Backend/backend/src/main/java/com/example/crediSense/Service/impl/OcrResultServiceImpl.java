package com.example.crediSense.Service.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.crediSense.Service.OcrResultService;
import com.example.crediSense.dto.request.OcrResultRequest;
import com.example.crediSense.dto.response.OcrResultResponse;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.entity.OcrResult;
import com.example.crediSense.repository.FichierRepository;
import com.example.crediSense.repository.OcrResultRepository;

import lombok.RequiredArgsConstructor;


@Service
@RequiredArgsConstructor
public class OcrResultServiceImpl implements OcrResultService {

    private final OcrResultRepository ocrResultRepository;
    private final FichierRepository fichierRepository;

    @Override
    public OcrResultResponse create(OcrResultRequest request) {
        Fichier fichier = fichierRepository.findById(request.getFichierId())
                .orElseThrow(() -> new RuntimeException("Fichier non trouvé"));
        OcrResult ocr = new OcrResult();
        ocr.setTexteBrut(request.getTexteBrut());
        ocr.setTexteNettoye(request.getTexteNettoye());
        ocr.setStatut(request.getStatut());
        ocr.setFichier(fichier);
        return toResponse(ocrResultRepository.save(ocr));
    }

    @Override
    public OcrResultResponse getById(UUID id) {
        return toResponse(ocrResultRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("OcrResult non trouvé : " + id)));
    }
     @Override
    public OcrResultResponse getByFichierId(UUID fichierId) {
        return toResponse(ocrResultRepository.findByFichierId(fichierId)
                .orElseThrow(() -> new RuntimeException("Aucun OCR pour le fichier : " + fichierId)));
    }

    @Override
    public List<OcrResultResponse> getAll() {
        return ocrResultRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public OcrResultResponse update(UUID id, OcrResultRequest request) {
        OcrResult ocr = ocrResultRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("OcrResult non trouvé : " + id));
        ocr.setTexteBrut(request.getTexteBrut());
        ocr.setTexteNettoye(request.getTexteNettoye());
        ocr.setStatut(request.getStatut());
        return toResponse(ocrResultRepository.save(ocr));
    }

    @Override
    public void delete(UUID id) {
        ocrResultRepository.deleteById(id);
    }

    private OcrResultResponse toResponse(OcrResult ocr) {
        OcrResultResponse response = new OcrResultResponse();
        response.setId(ocr.getId());
        response.setTexteBrut(ocr.getTexteBrut());
        response.setTexteNettoye(ocr.getTexteNettoye());
        response.setStatut(ocr.getStatut());
        response.setFichierId(ocr.getFichier().getId());
        response.setCreatedAt(ocr.getCreatedAt());
        return response;
    }
}