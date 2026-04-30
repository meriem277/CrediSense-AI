package com.example.crediSense.Service;
import com.example.crediSense.dto.request.RagContextRequest;
import com.example.crediSense.dto.response.RagContextResponse;

import java.util.List;
import java.util.UUID;

public interface RagContextService {

    RagContextResponse create(RagContextRequest request);

    RagContextResponse getById(UUID id);

    List<RagContextResponse> getAll();

    List<RagContextResponse> getByCin(String cin);

    List<RagContextResponse> getByDossier(UUID dossierId);

    RagContextResponse update(UUID id, RagContextRequest request);

    void delete(UUID id);

    // 🔥 pour ton RAG
    String buildContextForCin(String cin);
}