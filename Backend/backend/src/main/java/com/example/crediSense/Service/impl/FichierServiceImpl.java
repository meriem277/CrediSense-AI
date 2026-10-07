package com.example.crediSense.Service.impl;

import com.example.crediSense.Service.FichierService;
import com.example.crediSense.dto.request.FichierRequest;
import com.example.crediSense.dto.response.FichierResponse;
import com.example.crediSense.entity.*;
import com.example.crediSense.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final JsonExtractionRepository jsonExtractionRepository;  // ✅ nouveau
    private final Doctrclientservice   doctrclientservice;
    private final RestTemplate         restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();     // ✅ nouveau

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

                    // Fichier absent du disque (volume non monté, conteneur recréé,
                    // fichier supprimé) : message clair au lieu d'une erreur d'E/S obscure
                    if (!Files.exists(path)) {
                        log.warn("Fichier introuvable sur le serveur : {}", path);
                        enregistrerOcr(f, null, "FAILED",
                                "Fichier introuvable sur le serveur (supprimé ou déplacé) : "
                                        + "le document doit être renvoyé");
                        continue;
                    }

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
                        Map reponseOcr = ocrResponse.getBody();
                        Object texte = reponseOcr.get("texte");
                        boolean ocrOk = texte != null && !texte.toString().isBlank()
                                && !"FAILURE".equals(String.valueOf(reponseOcr.get("statut")));

                        // ❌ Format non supporté, image illisible, aucun texte lu… :
                        // on garde la raison au lieu d'ignorer le fichier en silence
                        if (!ocrOk) {
                            Object erreurIa = reponseOcr.get("erreur");
                            String raison = erreurIa != null && !erreurIa.toString().isBlank()
                                    ? erreurIa.toString()
                                    : "Aucun texte lisible dans le document";
                            log.warn("OCR sans résultat pour {} : {}", f.getNomOriginal(), raison);
                            enregistrerOcr(f, null, "FAILED", raison);
                        }

                        if (ocrOk) {

                            // ✅ Sauvegarde dans ocr_results
                            enregistrerOcr(f, texte.toString(), "SUCCESS", null);
                            log.info("OCR sauvegardé pour fichier {}", f.getNomOriginal());

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
                                jsonBody.put("type_document", f.getTypeDocument());

                                HttpEntity<Map<String, Object>> jsonRequest =
                                        new HttpEntity<>(jsonBody, jsonHeaders);

                                Map extractResponse = restTemplate.postForObject(
                                        nlpServiceUrl + "/ai/extract-json",
                                        jsonRequest, Map.class
                                );

                                // ✅ Persistance de l'extraction JSON, liée au fichier —
                                // c'est cette table que toResponse() relit ensuite pour
                                // remplir "verifie" et "jsonData" côté frontend.
                                if (extractResponse != null) {
                                    try {
                                        Object jsonData        = extractResponse.get("json_data");
                                        Object confidenceScore = extractResponse.get("confidence_score");

                                        JsonExtraction extraction = JsonExtraction.builder()
                                                .cin(cin)
                                                .jsonData(objectMapper.writeValueAsString(jsonData))
                                                .confidenceScore(confidenceScore != null
                                                        ? Double.parseDouble(confidenceScore.toString())
                                                        : null)
                                                .fichier(f)
                                                .build();

                                        jsonExtractionRepository.save(extraction);
                                        log.info("JsonExtraction sauvegardée pour {} (fichier_id={})",
                                                f.getNomOriginal(), f.getId());
                                    } catch (Exception e) {
                                        log.warn("Erreur sauvegarde JsonExtraction pour {}: {}",
                                                f.getNomOriginal(), e.getMessage());
                                    }
                                }

                                log.info("JSON extrait pour {} (type={})",
                                        f.getNomOriginal(), f.getTypeDocument());

                            } catch (Exception e) {
                                log.warn("Extraction JSON échouée pour {}: {}",
                                        f.getNomOriginal(), e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("OCR échoué pour {}: {}", f.getNomOriginal(),
                            e.getMessage());
                    enregistrerOcr(f, null, "FAILED",
                            "Service OCR indisponible ou en erreur : " + e.getMessage());
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

    /**
     * Crée ou met à jour le résultat OCR d'un fichier (un seul par fichier).
     * Réutiliser l'enregistrement existant évite qu'une nouvelle analyse échoue
     * sur la contrainte d'unicité et laisse un ancien résultat périmé.
     */
    private void enregistrerOcr(Fichier f, String texte, String statut, String erreur) {
        try {
            OcrResult ocr = ocrResultRepository.findByFichierId(f.getId())
                    .orElseGet(() -> OcrResult.builder().fichier(f).build());
            ocr.setTexteBrut(texte);
            ocr.setTexteNettoye(texte);
            ocr.setStatut(statut);
            ocr.setErreur(erreur);
            ocrResultRepository.save(ocr);
        } catch (Exception e) {
            log.warn("Erreur sauvegarde OCR ({}): {}", f.getNomOriginal(), e.getMessage());
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

        if (f.getAgent() != null)   r.setAgentId(f.getAgent().getId());
        if (f.getDossier() != null) r.setDossierId(f.getDossier().getId());

        // ✅ Récupère la dernière extraction JSON de ce fichier pour déterminer
        // "verifie" et exposer les données extraites (nomClient, prenomClient, cin...)
        // au frontend — c'est ce qui manquait pour que le pipeline visuel fonctionne.
        try {
            List<JsonExtraction> extractions = jsonExtractionRepository.findByFichierIdOrderByCreatedAtDesc(f.getId());

            if (extractions != null && !extractions.isEmpty()) {
                JsonExtraction derniere = extractions.get(0);   // ✅ triée DESC — index 0 = la plus récente

                if (derniere.getJsonData() != null && !derniere.getJsonData().isBlank()) {
                    Map<String, Object> parsed = objectMapper.readValue(
                            derniere.getJsonData(), new TypeReference<Map<String, Object>>() {}
                    );
                    r.setJsonData(parsed);
                    r.setVerifie(true);

                    // ✅ Cohérence du CIN — compare le "cin" extrait de CE fichier
                    // au CIN officiel du client du dossier. Source de vérité
                    // calculée ici, pas côté frontend, car elle repose sur
                    // dossier.client.cin (donnée sensible/authentique).
                    Object cinExtraitObj = parsed.get("cin");
                    String cinExtrait = cinExtraitObj != null ? cinExtraitObj.toString() : null;
                    String cinAttendu = (f.getDossier() != null && f.getDossier().getClient() != null)
                            ? f.getDossier().getClient().getCin() : null;

                    if (cinExtrait != null && !cinExtrait.isBlank() && cinAttendu != null) {
                        boolean coherent = normaliserCin(cinExtrait).equals(normaliserCin(cinAttendu));
                        r.setCinCoherent(coherent);
                        if (!coherent) {
                            log.warn("CIN incohérent — fichier={} ({}), extrait='{}', attendu='{}'",
                                    f.getId(), f.getTypeDocument(), cinExtrait, cinAttendu);
                        }
                    } else {
                        // Pas de "cin" extrait sur ce document (ex: justificatif de
                        // domicile) — rien à comparer, on ne pénalise pas.
                        r.setCinCoherent(null);
                    }
                } else {
                    r.setVerifie(false);
                }
            } else {
                r.setVerifie(false);
            }
        } catch (Exception e) {
            log.warn("Impossible de lire l'extraction JSON pour fichier {}: {}",
                    f.getId(), e.getMessage());
            r.setVerifie(false);
        }

        return r;
    }

    // ✅ Normalise un numéro CIN pour comparaison — garde uniquement les chiffres,
    // pour absorber les variations de format issues de l'OCR (espaces, tirets...).
    private String normaliserCin(String s) {
        if (s == null) return "";
        return s.replaceAll("[^0-9]", "");
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

                Dossier dossier = dossierRepository.findById(dossierUUID).orElse(null);

                // ✅ Injecte les infos de la demande (montant, durée, type de
                // contrat) — ces données existent sur le Dossier (remplies au
                // formulaire client) mais n'apparaissent dans AUCUN document
                // uploadé (CIN, fiche de paie...). Sans ça, l'agent Python ne
                // peut pas connaître le montant demandé ni calculer la
                // mensualité estimée, d'où les "0 TND" affichés côté agent.
                if (dossier != null) {
                    StringBuilder demandeInfo = new StringBuilder();
                    demandeInfo.append("=== DEMANDE DE CREDIT (formulaire client) ===\n");
                    if (dossier.getMontantCredit() != null) {
                        demandeInfo.append("Montant demande: ")
                                .append(dossier.getMontantCredit()).append(" TND\n");
                    }
                    if (dossier.getDureeCredit() != null) {
                        demandeInfo.append("Duree souhaitee: ")
                                .append(dossier.getDureeCredit()).append(" mois\n");
                    }
                    if (dossier.getTypeContrat() != null) {
                        demandeInfo.append("Type de contrat souhaite: ")
                                .append(dossier.getTypeContrat()).append("\n");
                    }
                    demandeInfo.append("\n");

                    texteComplet.insert(0, demandeInfo);
                }

                // ── ✅ Vérification identité client vs documents ──────────
                Map<String, Object> alerteIdentite = verifierIdentiteClient(
                        texteComplet.toString(), dossier, headers
                );

                Map<String, Object> body = new HashMap<>();
                body.put("document_text", texteComplet.toString());

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

                Map result = restTemplate.postForObject(
                        nlpServiceUrl + "/ai/score/consommation",
                        request, Map.class
                );

                if (result != null) {

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

                            // ✅ Sauvegarde la réponse complète de l'agent (financialMetrics,
                            // risks, recommendedPlan, documentSources...) — sans ça, ce
                            // détail riche n'existe qu'en mémoire côté Angular le temps de
                            // la session et disparaît dès qu'on quitte l'onglet.
                            try {
                                df.setResultatComplet(objectMapper.writeValueAsString(result));
                            } catch (Exception e) {
                                log.warn("Impossible de sérialiser le résultat complet pour dossier {}: {}",
                                        dossierId, e.getMessage());
                            }

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

                    // ✅ Injecte l'alerte d'identité dans le résultat final
                    result.putAll(alerteIdentite);

                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("Score échoué: {}", e.getMessage());
        }

        return new HashMap<>();
    }

    // ── ✅ Vérifie que les documents correspondent bien au client du dossier ──
    private Map<String, Object> verifierIdentiteClient(
            String texteComplet, Dossier dossier, HttpHeaders headers) {

        Map<String, Object> alerte = new HashMap<>();
        alerte.put("alerteIdentite", false);
        alerte.put("messageIdentite", "");

        if (dossier == null || dossier.getClient() == null) {
            return alerte;
        }

        try {
            Map<String, Object> extractBody = new HashMap<>();
            extractBody.put("texte_nettoye", texteComplet);
            extractBody.put("cin", dossier.getClient().getCin());

            HttpEntity<Map<String, Object>> extractRequest =
                    new HttpEntity<>(extractBody, headers);

            Map extractResult = restTemplate.postForObject(
                    nlpServiceUrl + "/ai/extract-json",
                    extractRequest, Map.class
            );

            if (extractResult == null) return alerte;

            Map<String, Object> jsonData = (Map<String, Object>)
                    extractResult.getOrDefault("json_data", new HashMap<>());

            String nomExtrait    = normaliser((String) jsonData.get("nomClient"));
            String prenomExtrait = normaliser((String) jsonData.get("prenomClient"));

            String nomAttendu    = normaliser(dossier.getClient().getNom());
            String prenomAttendu = normaliser(dossier.getClient().getPrenom());

            boolean nomManquant = nomExtrait == null || nomExtrait.isBlank()
                    || prenomExtrait == null || prenomExtrait.isBlank();

            if (nomManquant) {
                return alerte; // pas assez d'info pour comparer, on ne pénalise pas
            }

            boolean nomMatch    = nomAttendu    != null && nomAttendu.contains(nomExtrait);
            boolean prenomMatch = prenomAttendu != null && prenomAttendu.contains(prenomExtrait);

            if (!nomMatch || !prenomMatch) {
                alerte.put("alerteIdentite", true);
                alerte.put("messageIdentite", String.format(
                        "Les documents mentionnent \"%s %s\", mais le dossier appartient à \"%s %s\". " +
                                "Vérification manuelle recommandée.",
                        capitalize(prenomExtrait), capitalize(nomExtrait),
                        dossier.getClient().getPrenom(), dossier.getClient().getNom()
                ));
                log.warn("Alerte identité — dossier={} : documents='{} {}' vs client='{} {}'",
                        dossier.getId(), prenomExtrait, nomExtrait,
                        dossier.getClient().getPrenom(), dossier.getClient().getNom());
            }

        } catch (Exception e) {
            log.warn("Vérification identité échouée: {}", e.getMessage());
        }

        return alerte;
    }

    private String normaliser(String s) {
        if (s == null) return null;
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // ✅ Helper
    private Double toDouble(Object val) {
        if (val == null) return null;
        try { return Double.parseDouble(val.toString()); }
        catch (Exception e) { return null; }
    }
}