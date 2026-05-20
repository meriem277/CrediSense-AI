package com.example.crediSense.Service.impl;

import com.example.crediSense.Service.FichierService;
import com.example.crediSense.Service.impl.PdfConversionService;
import com.example.crediSense.dto.request.FichierRequest;
import com.example.crediSense.dto.response.FichierResponse;
import com.example.crediSense.entity.Agent;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.repository.AgentRepository;
import com.example.crediSense.repository.FichierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FichierServiceImpl implements FichierService {

    private final FichierRepository    fichierRepository;
    private final AgentRepository      agentRepository;
    private final PdfConversionService pdfConversionService;  // P majuscule

    @Value("${upload.base-path:uploads}")
    private String basePath;

    // ─── Upload + Conversion PDF ──────────────────────────────────────────────

    @Override
    public FichierResponse uploadAndConvert(MultipartFile file, String cin, UUID agentId) {
        try {
            String nomOriginal = file.getOriginalFilename();
            String extension   = getExtension(nomOriginal);

            Path dossierOriginal = Paths.get(basePath, "originaux", cin);
            Path dossierPdf      = Paths.get(basePath, "pdf", cin);
            Files.createDirectories(dossierOriginal);
            Files.createDirectories(dossierPdf);

            String fichierId    = UUID.randomUUID().toString();
            Path   cheminSource = dossierOriginal.resolve(fichierId + "." + extension);
            Files.copy(file.getInputStream(), cheminSource, StandardCopyOption.REPLACE_EXISTING);
            log.info("Fichier original sauvegardé : {}", cheminSource);

            Path cheminPdf = dossierPdf.resolve(fichierId + ".pdf");
            pdfConversionService.convert(cheminSource, cheminPdf, extension);

            Agent agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new RuntimeException("Agent introuvable : " + agentId));

            Fichier fichier = Fichier.builder()
                    .cin(cin)
                    .nomOriginal(nomOriginal)
                    .typeOriginal(extension)
                    .cheminPdf(cheminPdf.toString())
                    .agent(agent)
                    .build();

            Fichier saved = fichierRepository.save(fichier);
            log.info("Fichier enregistré en BDD avec id={}", saved.getId());
            return toResponse(saved);

        } catch (Exception e) {
            log.error("Erreur upload/conversion : {}", e.getMessage(), e);
            throw new RuntimeException("Erreur upload : " + e.getMessage(), e);
        }
    }

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    @Override
    public FichierResponse create(FichierRequest request) {
        Agent agent = agentRepository.findById(request.getAgentId())
                .orElseThrow(() -> new RuntimeException("Agent non trouvé"));
        Fichier fichier = Fichier.builder()
                .cin(request.getCin())
                .nomOriginal(request.getNomOriginal())
                .typeOriginal(request.getTypeOriginal())
                .cheminPdf(request.getCheminPdf())
                .agent(agent)
                .build();
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

    // ─── Helper unique ────────────────────────────────────────────────────────

    private FichierResponse toResponse(Fichier f) {
        FichierResponse response = new FichierResponse();
        response.setId(f.getId());
        response.setCin(f.getCin());
        response.setNomOriginal(f.getNomOriginal());
        response.setTypeOriginal(f.getTypeOriginal());
        response.setCheminPdf(f.getCheminPdf());
        response.setAgentId(f.getAgent() != null ? f.getAgent().getId() : null);
        response.setCreatedAt(f.getCreatedAt());
        return response;
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            throw new IllegalArgumentException("Nom de fichier invalide : " + filename);
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}