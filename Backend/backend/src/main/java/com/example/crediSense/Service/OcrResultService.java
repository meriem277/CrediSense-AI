package com.example.crediSense.Service;

import java.util.List;
import java.util.UUID;

import com.example.crediSense.dto.request.OcrResultRequest;
import com.example.crediSense.dto.response.OcrResultResponse;

public interface OcrResultService {
        OcrResultResponse create(OcrResultRequest request);
    OcrResultResponse getById(UUID id);
    OcrResultResponse getByFichierId(UUID fichierId);
    List<OcrResultResponse> getAll();
    OcrResultResponse update(UUID id, OcrResultRequest request);
    void delete(UUID id);
    
}
