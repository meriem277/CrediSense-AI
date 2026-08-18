package com.example.crediSense.Service.impl;

import com.example.crediSense.Service.FichierService;
import com.example.crediSense.dto.request.FichierRequest;
import com.example.crediSense.dto.response.FichierResponse;
import com.example.crediSense.entity.*;
import com.example.crediSense.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FichierServiceImpl implements FichierService {

    private final FichierRepository    fichierRepository;
    private final AgentRepository      agentRepository;
    private final DossierRepository    dossierRepository;
    private final OcrResultRepository  ocrResultRepository;
    private final AgentAnalysisRepository  agentAnalysisRepository;
    private final DecisionFinaleRepository decisionFinaleRepository;
    private final Doctrclientservice   doctrclientservice;
    private final RestTemplate         restTemplate;

    @Value("${upload.base-path}")
    private String uploadBasePath;

    @Value("${nlp.service.url}")
    private String nlpServiceUrl;

    // ── Interface obligatoire ─────────────────────────────────────────
    @Override
    public FichierResponse create(FichierRequest request) {
        throw new UnsupportedOperationException("Utilisez uploadAndConvert");
    }

    // ── Upload + conversion PDF ───────────────────────────────────────
    @Override
    public FichierResponse uploadAndConvert(
            MultipartFile file, String cin,
            UUID agentId, UUID dossierId) {
        try {
            Agent agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new RuntimeException("Agent introuvable"));
            Dossier dossier = dossierRepository.findById(dossierId)
                    .orElseThrow(() -> new RuntimeException("Dossier introuvable"));

            String originalName = file.getOriginalFilename();
            String uploadDir    = uploadBasePath + "/client-uploads/" + cin;
            Files.createDirectories(Paths.get(uploadDir));
            Path savedPath = Paths.get(uploadDir,
                    UUID.randomUUID() + "_" + originalName);
            Files.copy(file.getInputStream(), savedPath,
                    StandardCopyOption.REPLACE_EXISTING);

            Fichier fichier = Fichier.builder()
                    .cin(cin)
                    .nomOriginal(originalName)
                    .typeOriginal(file.getContentType())
                    .typeDocument("AUTRE")
                    .cheminPdf(savedPath.toString())
                    .agent(agent)
                    .dossier(dossier)
                    .build();

            Fichier saved = fichierRepository.save(fichier);
            log.info("Fichier sauvegardé : {}", saved.getId());
            return toResponse(saved);

        } catch (Exception e) {
            log.error("Erreur upload: {}", e.getMessage());
            throw new RuntimeException("Erreur upload : " + e.getMessage(), e);
        }
    }

    // ── Pipeline complet ──────────────────────────────────────────────
    @Override
    public void analyserDossierComplet(String cin, String dossierId) {
        try {
            UUID dossierUUID = UUID.fromString(dossierId);
            List<Fichier> fichiers = fichierRepository.findByDossierId(dossierUUID);

            if (fichiers == null || fichiers.isEmpty()) {
                log.warn("Aucun fichier pour le dossier {}", dossierId);
                return;
            }

            StringBuilder texteComplet = new StringBuilder();

            // ── ÉTAPE 1 : OCR ─────────────────────────────────────────
            for (Fichier f : fichiers) {
                if (f.getCheminPdf() == null) continue;
                try {
                    Path path = Paths.get(f.getCheminPdf());

                    HttpHeaders multipartHeaders = new HttpHeaders();
                    multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

                    MultiValueMap<String, Object> ocrBody = new LinkedMultiValueMap<>();
                    ocrBody.add("file", new FileSystemResource(path.toFile()));

                    HttpEntity<MultiValueMap<String, Object>> ocrRequest =
                            new HttpEntity<>(ocrBody, multipartHeaders);

                    ResponseEntity<Map> ocrResponse = restTemplate.postForEntity(
                            nlpServiceUrl + "/ocr", ocrRequest, Map.class
                    );

                    if (ocrResponse.getBody() != null) {
                        Object texte = ocrResponse.getBody().get("texte");
                        if (texte != null && !texte.toString().isBlank()) {

                            // ✅ Sauvegarde dans ocr_results
                            try {
                                OcrResult ocrResult = OcrResult.builder()
                                        .texteBrut(texte.toString())
                                        .texteNettoye(texte.toString())
                                        .statut("SUCCESS")
                                        .fichier(f)
                                        .build();
                                ocrResultRepository.save(ocrResult);
                                log.info("OCR sauvegardé pour fichier {}",
                                        f.getNomOriginal());
                            } catch (Exception e) {
                                log.warn("Erreur sauvegarde OCR: {}", e.getMessage());
                            }

                            texteComplet.append("=== ")
                                    .append(f.getTypeDocument())
                                    .append(" ===\n")
                                    .append(texte)
                                    .append("\n\n");

                            // ── ÉTAPE 2 : Extraction JSON ──────────────
                            try {
                                HttpHeaders jsonHeaders = new HttpHeaders();
                                jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

                                Map<String, Object> jsonBody = new HashMap<>();
                                jsonBody.put("texte_nettoye", texte.toString());
                                jsonBody.put("cin",           cin);

                                HttpEntity<Map<String, Object>> jsonRequest =
                                        new HttpEntity<>(jsonBody, jsonHeaders);

                                restTemplate.postForObject(
                                        nlpServiceUrl + "/ai/extract-json",
                                        jsonRequest, Map.class
                                );
                                log.info("JSON extrait pour {}", f.getNomOriginal());

                            } catch (Exception e) {
                                log.warn("Extraction JSON échouée pour {}: {}",
                                        f.getNomOriginal(), e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("OCR échoué pour {}: {}", f.getNomOriginal(),
                            e.getMessage());
                }
            }

            if (texteComplet.length() == 0) {
                log.warn("Aucun texte extrait pour le dossier {}", dossierId);
                return;
            }

            HttpHeaders jsonHeaders = new HttpHeaders();
            jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

            // ── ÉTAPE 3 : Indexation RAG ──────────────────────────────
            try {
                List<String> ocrTextes = new ArrayList<>();
                List<Fichier> fichiersList = fichierRepository.findByDossierId(dossierUUID);

                for (Fichier f : fichiersList) {
                    ocrResultRepository.findByFichierId(f.getId()).ifPresent(ocr -> {
                        if (ocr.getTexteNettoye() != null && !ocr.getTexteNettoye().isBlank()) {
                            ocrTextes.add("Document type " + f.getTypeDocument() +
                                    " nom " + f.getNomOriginal() +
                                    " :\n" + ocr.getTexteNettoye());
                        }
                    });
                }

                if (!ocrTextes.isEmpty()) {
                    HttpHeaders chatHeaders = new HttpHeaders();
                    chatHeaders.setContentType(MediaType.APPLICATION_JSON);

                    Map<String, Object> chatBody = new HashMap<>();
                    chatBody.put("dossier_id", dossierId);
                    chatBody.put("cin",        cin);
                    chatBody.put("ocr_textes", ocrTextes);

                    HttpEntity<Map<String, Object>> chatRequest =
                            new HttpEntity<>(chatBody, chatHeaders);

                    restTemplate.postForObject(
                            nlpServiceUrl + "/ai/chat/index",
                            chatRequest, Map.class
                    );
                    log.info("RAG indexe avec {} documents pour dossier {}",
                            ocrTextes.size(), dossierId);

                    // ✅ Attente avant le score pour éviter le rate limit Groq
                    Thread.sleep(3000);
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Indexation RAG echouee: {}", e.getMessage());
            }

            // ── ÉTAPE 4 : Score consommation ──────────────────────────
            try {
                Map<String, Object> scoreBody = new HashMap<>();
                scoreBody.put("document_text", texteComplet.toString());

                HttpEntity<Map<String, Object>> scoreRequest =
                        new HttpEntity<>(scoreBody, jsonHeaders);

                restTemplate.postForObject(
                        nlpServiceUrl + "/ai/score/consommation",
                        scoreRequest, Map.class
                );
                log.info("Score calculé pour dossier {}", dossierId);

            } catch (Exception e) {
                log.warn("Score consommation échoué: {}", e.getMessage());
            }

            log.info("Pipeline complet réussi pour dossier {} — {} chars",
                    dossierId, texteComplet.length());

        } catch (Exception e) {
            log.error("Erreur analyse complète dossier {}: {}",
                    dossierId, e.getMessage());
        }
    }

    // ── Fichiers par dossier ──────────────────────────────────────────
    @Override
    public List<Fichier> getByDossierId(UUID dossierId) {
        return fichierRepository.findByDossierId(dossierId);
    }

    // ── CRUD ──────────────────────────────────────────────────────────
    @Override
    public FichierResponse getById(UUID id) {
        return toResponse(fichierRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Fichier introuvable")));
    }

    @Override
    public List<FichierResponse> getByAgentId(UUID agentId) {
        return fichierRepository.findByAgentId(agentId)
                .stream().map(this::toResponse).toList();
    }

    @Override
    public List<FichierResponse> getByCin(String cin) {
        return fichierRepository.findByCin(cin)
                .stream().map(this::toResponse).toList();
    }

    @Override
    public List<FichierResponse> getAll() {
        return fichierRepository.findAll()
                .stream().map(this::toResponse).toList();
    }

    @Override
    public void delete(UUID id) {
        fichierRepository.deleteById(id);
    }

    // ── Helper ────────────────────────────────────────────────────────
    private FichierResponse toResponse(Fichier f) {
        FichierResponse r = new FichierResponse();
        r.setId(f.getId());
        r.setCin(f.getCin());
        r.setNomOriginal(f.getNomOriginal());
        r.setTypeOriginal(f.getTypeOriginal());
        r.setTypeDocument(f.getTypeDocument());
        r.setCheminPdf(f.getCheminPdf());
        r.setCreatedAt(f.getCreatedAt());
        return r;
    }
    @Override
    public Map analyserEtScorer(String cin, String dossierId) {
        analyserDossierComplet(cin, dossierId);

        try {
            UUID dossierUUID = UUID.fromString(dossierId);
            List<Fichier> fichiers = fichierRepository.findByDossierId(dossierUUID);

            StringBuilder texteComplet = new StringBuilder();
            for (Fichier f : fichiers) {
                ocrResultRepository.findByFichierId(f.getId()).ifPresent(ocr -> {
                    if (ocr.getTexteNettoye() != null) {
                        texteComplet.append(ocr.getTexteNettoye()).append("\n\n");
                    }
                });
            }

            if (texteComplet.length() > 0) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                Map<String, Object> body = new HashMap<>();
                body.put("document_text", texteComplet.toString());

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

                Map result = restTemplate.postForObject(
                        nlpServiceUrl + "/ai/score/consommation",
                        request, Map.class
                );

                if (result != null) {

                    Dossier dossier = dossierRepository.findById(dossierUUID).orElse(null);

                    if (dossier != null) {

                        String decision = result.get("eligibility") != null
                                ? result.get("eligibility").toString() : "";

                        // ── Sauvegarde AgentAnalysis ──────────────────────────
                        try {
                            Map<String, Object> metrics = (Map<String, Object>)
                                    result.getOrDefault("financialMetrics", new HashMap<>());

                            AgentAnalysis analysis = agentAnalysisRepository
                                    .findByDossierId(dossierUUID)
                                    .orElse(AgentAnalysis.builder().dossier(dossier).build());

                            analysis.setTypeAgent("CONSOMMATION");
                            analysis.setScore(toDouble(result.get("eligibilityScore")));
                            analysis.setRevenus(toDouble(metrics.get("monthlyIncome")));
                            analysis.setEndettement(toDouble(metrics.get("dti")));
                            analysis.setDecision(decision);
                            analysis.setJustification(result.get("rawExplanation") != null
                                    ? result.get("rawExplanation").toString() : "");

                            agentAnalysisRepository.save(analysis);
                            log.info("AgentAnalysis sauvegardé pour dossier {}", dossierId);

                        } catch (Exception e) {
                            log.warn("Erreur sauvegarde AgentAnalysis: {}", e.getMessage());
                        }

                        // ── Sauvegarde DecisionFinale ─────────────────────────
                        try {
                            String justification = result.get("rawExplanation") != null
                                    ? result.get("rawExplanation").toString() : "";

                            String explicationClient = switch (decision) {
                                case "ELIGIBLE"     ->
                                        "Félicitations ! Votre dossier a été analysé et vous êtes éligible au crédit demandé.";
                                case "REFUS"        ->
                                        "Nous regrettons de vous informer que votre dossier ne remplit pas les critères d'éligibilité.";
                                case "CONDITIONNEL" ->
                                        "Votre dossier est accepté sous conditions. Des garanties supplémentaires peuvent être requises.";
                                default             ->
                                        "Votre dossier est en cours d'analyse.";
                            };

                            DecisionFinale df = decisionFinaleRepository
                                    .findByDossierId(dossierUUID)
                                    .orElse(DecisionFinale.builder().dossier(dossier).build());

                            df.setScoreFinal(toDouble(result.get("eligibilityScore")));
                            df.setDecisionFinale(decision);
                            df.setJustificationGlobale(justification);
                            df.setExplicationClient(explicationClient);

                            decisionFinaleRepository.save(df);
                            log.info("DecisionFinale sauvegardée — dossier={}, decision={}",
                                    dossierId, decision);

                        } catch (Exception e) {
                            log.warn("Erreur sauvegarde DecisionFinale: {}", e.getMessage());
                        }

                        // ── Mise à jour statut dossier ────────────────────────
                        try {
                            String nouveauStatut = switch (decision) {
                                case "ELIGIBLE"     -> "APPROUVE";
                                case "REFUS"        -> "REFUSE";
                                case "CONDITIONNEL" -> "EN_COURS";
                                default             -> "EN_COURS";
                            };

                            dossier.setStatut(nouveauStatut);
                            dossierRepository.save(dossier);
                            log.info("Dossier {} → statut {}", dossierUUID, nouveauStatut);

                        } catch (Exception e) {
                            log.warn("Erreur mise à jour statut: {}", e.getMessage());
                        }
                    }

                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("Score échoué: {}", e.getMessage());
        }

        return new HashMap<>();
    }
    // ✅ Helper
    private Double toDouble(Object val) {
        if (val == null) return null;
        try { return Double.parseDouble(val.toString()); }
        catch (Exception e) { return null; }
    }
}