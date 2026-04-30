package com.example.crediSense.Service.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.crediSense.Service.RagContextService;
import com.example.crediSense.dto.request.RagContextRequest;
import com.example.crediSense.dto.response.RagContextResponse;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.entity.RagContext;
import com.example.crediSense.repository.DossierRepository;
import com.example.crediSense.repository.RagContextRepository;

import lombok.RequiredArgsConstructor;
@Service
@RequiredArgsConstructor
public class RagContextServiceImpl implements RagContextService {

    private final RagContextRepository ragContextRepository;
    private final DossierRepository dossierRepository;
@Override
    public RagContextResponse create(RagContextRequest request) {

        RagContext context = new RagContext();
        context.setCin(request.getCin());
        context.setContexte(request.getContexte());
        context.setSource(request.getSource());

        // 🔗 lien dossier
        if (request.getDossierId() != null) {
            Dossier dossier = dossierRepository.findById(request.getDossierId())
                    .orElseThrow(() -> new RuntimeException("Dossier non trouvé"));
            context.setDossier(dossier);
        }

        return toResponse(ragContextRepository.save(context));
    }

    @Override
    public RagContextResponse getById(UUID id) {
        return toResponse(ragContextRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("RagContext non trouvé : " + id)));
    }
 @Override
    public List<RagContextResponse> getAll() {
        return ragContextRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<RagContextResponse> getByCin(String cin) {
        return ragContextRepository.findByCin(cin).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<RagContextResponse> getByDossier(UUID dossierId) {
        return ragContextRepository.findByDossierId(dossierId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
     @Override
    public RagContextResponse update(UUID id, RagContextRequest request) {

        RagContext context = ragContextRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("RagContext non trouvé : " + id));

        context.setCin(request.getCin());
        context.setContexte(request.getContexte());
        context.setSource(request.getSource());

        if (request.getDossierId() != null) {
            Dossier dossier = dossierRepository.findById(request.getDossierId())
                    .orElseThrow(() -> new RuntimeException("Dossier non trouvé"));
            context.setDossier(dossier);
        }

        return toResponse(ragContextRepository.save(context));
    }

    @Override
    public void delete(UUID id) {
        ragContextRepository.deleteById(id);
    }
       @Override
    public String buildContextForCin(String cin) {
        List<RagContext> contexts = ragContextRepository.findByCin(cin);

        StringBuilder sb = new StringBuilder();

        for (RagContext ctx : contexts) {
            sb.append(ctx.getContexte())
              .append("\n---\n");
        }

        return sb.toString();
    }

    // 🔁 Mapper Entity -> Response
    private RagContextResponse toResponse(RagContext context) {
        RagContextResponse response = new RagContextResponse();

        response.setId(context.getId());
        response.setCin(context.getCin());
        response.setContexte(context.getContexte());
        response.setSource(context.getSource());
        response.setCreatedAt(context.getCreatedAt());

        if (context.getDossier() != null) {
            response.setDossierId(context.getDossier().getId());
        }

        return response;
    }
}
