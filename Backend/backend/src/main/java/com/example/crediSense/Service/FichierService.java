package com.example.crediSense.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.crediSense.dto.request.FichierRequest;
import com.example.crediSense.dto.response.FichierResponse;
import com.example.crediSense.entity.Fichier;
import org.springframework.web.multipart.MultipartFile;

public interface FichierService {
     FichierResponse create(FichierRequest request);
    FichierResponse getById(UUID id);
    List<FichierResponse> getByAgentId(UUID agentId);
    List<FichierResponse> getByCin(String cin);
    List<FichierResponse> getAll();
    void delete(UUID id);
    FichierResponse uploadAndConvert(MultipartFile file, String cin, UUID agentId,UUID dossierId) ;

    List<Fichier> getByDossierId(UUID dossierId);

    /** Étape « Vérifier les documents » : OCR + extraction + cohérence du CIN, sans score. */
    Map verifierDossier(String cin, String dossierId);

    void analyserDossierComplet(String cin, String dossierId);

    /**
     * Analyse et score le dossier. Si le CIN est incohérent sur un document et que
     * {@code confirmerIncoherence} est faux, renvoie {"bloque": "CIN_INCOHERENT", ...}
     * sans calculer de score.
     */
    Map analyserEtScorer(String cin, String dossierId, boolean confirmerIncoherence);
}
