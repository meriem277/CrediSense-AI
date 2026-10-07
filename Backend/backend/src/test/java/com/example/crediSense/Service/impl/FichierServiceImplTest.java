package com.example.crediSense.Service.impl;

import com.example.crediSense.entity.Client;
import com.example.crediSense.entity.Dossier;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.entity.JsonExtraction;
import com.example.crediSense.entity.OcrResult;
import com.example.crediSense.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests des deux étapes « Vérifier » puis « Analyser » :
 *  - un CIN incohérent bloque le scoring tant que l'agent n'a pas confirmé ;
 *  - un document déjà lu et extrait n'est pas retraité (ni OCR, ni appel LLM) ;
 *  - une extraction en échec n'est pas enregistrée (elle ne « valide » pas le document).
 * Aucune base de données ni service IA : tout est simulé.
 */
class FichierServiceImplTest {

    private static final String CIN_CLIENT = "11111111";

    private FichierRepository        fichierRepository;
    private OcrResultRepository      ocrResultRepository;
    private JsonExtractionRepository jsonExtractionRepository;
    private DossierRepository        dossierRepository;
    private RestTemplate             restTemplate;
    private FichierServiceImpl       service;

    private final UUID dossierId = UUID.randomUUID();
    private Fichier    fichier;

    @TempDir
    Path dossierTemporaire;

    @BeforeEach
    void preparer() throws Exception {
        fichierRepository        = mock(FichierRepository.class);
        ocrResultRepository      = mock(OcrResultRepository.class);
        jsonExtractionRepository = mock(JsonExtractionRepository.class);
        dossierRepository        = mock(DossierRepository.class);
        restTemplate             = mock(RestTemplate.class);

        service = new FichierServiceImpl(
                fichierRepository,
                mock(AgentRepository.class),
                dossierRepository,
                ocrResultRepository,
                mock(AgentAnalysisRepository.class),
                mock(DecisionFinaleRepository.class),
                jsonExtractionRepository,
                null,                    // Doctrclientservice : non utilisé ici
                restTemplate
        );
        ReflectionTestUtils.setField(service, "nlpServiceUrl", "http://ai:8002");
        ReflectionTestUtils.setField(service, "uploadBasePath", dossierTemporaire.toString());

        Client client = new Client();
        client.setCin(CIN_CLIENT);
        Dossier dossier = Dossier.builder().id(dossierId).client(client).build();

        Path pdf = Files.writeString(dossierTemporaire.resolve("cin.pdf"), "%PDF-1.4 test");
        fichier = Fichier.builder()
                .id(UUID.randomUUID())
                .cin(CIN_CLIENT)
                .nomOriginal("cin.pdf")
                .typeDocument("CIN")
                .cheminPdf(pdf.toString())
                .dossier(dossier)
                .build();

        when(fichierRepository.findByDossierId(dossierId)).thenReturn(List.of(fichier));
        when(dossierRepository.findById(dossierId)).thenReturn(Optional.of(dossier));
    }

    // ── Outils ───────────────────────────────────────────────────────────────

    /** Le document a déjà un texte OCR réussi et une extraction avec le CIN indiqué. */
    private void documentDejaVerifie(String cinLu) {
        OcrResult ocr = OcrResult.builder().statut("SUCCESS").texteNettoye("CIN texte").fichier(fichier).build();
        when(ocrResultRepository.findByFichierId(fichier.getId())).thenReturn(Optional.of(ocr));

        JsonExtraction extraction = JsonExtraction.builder()
                .jsonData("{\"nomClient\":\"Ben Ali\",\"prenomClient\":\"Sami\",\"cin\":\"" + cinLu + "\"}")
                .fichier(fichier).build();
        when(jsonExtractionRepository.findByFichierIdOrderByCreatedAtDesc(fichier.getId()))
                .thenReturn(List.of(extraction));
    }

    private void reponseScoring() {
        Map<String, Object> score = new HashMap<>();
        score.put("eligibility", "ELIGIBLE");
        score.put("eligibilityScore", 75);
        when(restTemplate.postForObject(contains("/ai/score/consommation"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(score);
    }

    private void verifierScoringAppele(int fois) {
        verify(restTemplate, times(fois)).postForObject(
                contains("/ai/score/consommation"), any(HttpEntity.class), eq(Map.class));
    }

    // ── Garde « CIN incohérent » ─────────────────────────────────────────────

    @Test
    void cinIncoherentSansConfirmation_bloqueEtNeCalculePasDeScore() {
        documentDejaVerifie("22222222");   // le CIN lu ≠ celui du client

        Map resultat = service.analyserEtScorer(CIN_CLIENT, dossierId.toString(), false);

        assertEquals("CIN_INCOHERENT", resultat.get("bloque"));
        assertEquals(List.of("CIN"), resultat.get("typesIncoherents"));
        verifierScoringAppele(0);
        verify(restTemplate, never()).postForObject(contains("/ai/chat/index"), any(), any());
    }

    @Test
    void cinIncoherentAvecConfirmation_poursuitEtCalculeLeScore() {
        documentDejaVerifie("22222222");
        reponseScoring();

        Map resultat = service.analyserEtScorer(CIN_CLIENT, dossierId.toString(), true);

        assertNull(resultat.get("bloque"));
        assertEquals("ELIGIBLE", resultat.get("eligibility"));
        verifierScoringAppele(1);
    }

    @Test
    void cinCoherent_calculeLeScoreSansConfirmation() {
        documentDejaVerifie(CIN_CLIENT);   // même CIN que le client
        reponseScoring();

        Map resultat = service.analyserEtScorer(CIN_CLIENT, dossierId.toString(), false);

        assertNull(resultat.get("bloque"));
        verifierScoringAppele(1);
    }

    @Test
    void cinCoherentAvecEspacesEtTirets_estReconnu() {
        documentDejaVerifie("1111 1111");   // l'OCR ajoute des espaces : même numéro
        reponseScoring();

        Map resultat = service.analyserEtScorer(CIN_CLIENT, dossierId.toString(), false);

        assertNull(resultat.get("bloque"));
        verifierScoringAppele(1);
    }

    // ── Étape « Vérifier » ───────────────────────────────────────────────────

    @Test
    void verifier_nEstPasRetraiteQuandLeDocumentEstDejaLuEtExtrait() {
        documentDejaVerifie(CIN_CLIENT);

        Map resultat = service.verifierDossier(CIN_CLIENT, dossierId.toString());

        // ni OCR, ni extraction LLM : le document était déjà vérifié
        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(Map.class));
        verify(restTemplate, never()).postForObject(contains("/ai/extract-json"), any(), eq(Map.class));
        assertEquals(1, resultat.get("total"));
        assertEquals(0, resultat.get("echecs"));
        assertEquals(List.of(), resultat.get("cinIncoherents"));
    }

    @Test
    void verifier_signaleLesTypesDontLeCinEstIncoherent() {
        documentDejaVerifie("22222222");

        Map resultat = service.verifierDossier(CIN_CLIENT, dossierId.toString());

        assertEquals(List.of("CIN"), resultat.get("cinIncoherents"));
    }

    @Test
    void extractionEnEchec_neEstPasEnregistreeEtLeDocumentResteAVerifier() {
        // Aucun résultat OCR ni extraction : le document doit être lu
        when(ocrResultRepository.findByFichierId(fichier.getId())).thenReturn(Optional.empty());
        when(jsonExtractionRepository.findByFichierIdOrderByCreatedAtDesc(fichier.getId())).thenReturn(List.of());

        Map<String, Object> ocr = Map.of("texte", "texte lu", "statut", "SUCCESS");
        when(restTemplate.postForEntity(contains("/ocr"), any(), eq(Map.class))).thenReturn(ResponseEntity.ok(ocr));

        Map<String, Object> extractionEchouee = new HashMap<>();
        extractionEchouee.put("statut", "FAILURE");
        extractionEchouee.put("erreur", "LLM saturé");
        extractionEchouee.put("json_data", new HashMap<>());
        when(restTemplate.postForObject(contains("/ai/extract-json"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(extractionEchouee);

        Map resultat = service.verifierDossier(CIN_CLIENT, dossierId.toString());

        verify(jsonExtractionRepository, never()).save(any());   // rien d'enregistré
        assertEquals(1, resultat.get("echecs"));                  // le document reste « à vérifier »
    }

    @Test
    void extractionReussie_estEnregistree() {
        when(ocrResultRepository.findByFichierId(fichier.getId())).thenReturn(Optional.empty());
        when(jsonExtractionRepository.findByFichierIdOrderByCreatedAtDesc(fichier.getId())).thenReturn(List.of());

        Map<String, Object> ocr = Map.of("texte", "texte lu", "statut", "SUCCESS");
        when(restTemplate.postForEntity(contains("/ocr"), any(), eq(Map.class))).thenReturn(ResponseEntity.ok(ocr));

        Map<String, Object> extractionOk = new HashMap<>();
        extractionOk.put("statut", "SUCCESS");
        extractionOk.put("json_data", Map.of("cin", CIN_CLIENT, "nomClient", "Ben Ali"));
        extractionOk.put("confidence_score", 0.9);
        when(restTemplate.postForObject(contains("/ai/extract-json"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(extractionOk);

        service.verifierDossier(CIN_CLIENT, dossierId.toString());

        verify(jsonExtractionRepository, times(1)).save(argThat(e -> e.getJsonData().contains(CIN_CLIENT)));
    }

    // ── Contrôle du type de document (classification) ────────────────────────

    /** Le document n'a pas encore été lu : OCR et extraction réussissent. */
    private void ocrEtExtractionReussis() {
        when(ocrResultRepository.findByFichierId(fichier.getId())).thenReturn(Optional.empty());
        when(jsonExtractionRepository.findByFichierIdOrderByCreatedAtDesc(fichier.getId())).thenReturn(List.of());

        Map<String, Object> ocr = Map.of("texte", "texte lu", "statut", "SUCCESS");
        when(restTemplate.postForEntity(contains("/ocr"), any(), eq(Map.class))).thenReturn(ResponseEntity.ok(ocr));

        Map<String, Object> extractionOk = new HashMap<>();
        extractionOk.put("statut", "SUCCESS");
        extractionOk.put("json_data", Map.of("cin", CIN_CLIENT, "nomClient", "Ben Ali"));
        extractionOk.put("confidence_score", 0.9);
        when(restTemplate.postForObject(contains("/ai/extract-json"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(extractionOk);
    }

    /** Réponse de /ai/classify-hybrid : bloc « controle » calculé par le service IA. */
    private void classification(String detecte, boolean fiable, Boolean concordant) {
        Map<String, Object> controle = new HashMap<>();
        controle.put("typeDetecte", detecte);
        controle.put("confiance", 0.8);
        controle.put("methode", "embeddings");
        controle.put("fiable", fiable);
        controle.put("concordant", concordant);
        controle.put("typeRetenu", fiable ? detecte : fichier.getTypeDocument());
        when(restTemplate.postForObject(contains("/ai/classify-hybrid"), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(Map.of("type_document", detecte, "controle", controle));
    }

    /** Corps JSON envoyé à /ai/extract-json. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> corpsExtraction() {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(contains("/ai/extract-json"), captor.capture(), eq(Map.class));
        return (Map<String, Object>) captor.getValue().getBody();
    }

    @Test
    void conflitDeType_estEnregistre_etL_extractionUtiliseLeTypeDetecte() {
        fichier.setTypeDocument("FICHE_PAIE");
        ocrEtExtractionReussis();
        classification("RELEVE_BANCAIRE", true, false);

        Map resultat = service.verifierDossier(CIN_CLIENT, dossierId.toString());

        assertEquals(Boolean.TRUE, fichier.getTypeConflit());
        assertEquals("RELEVE_BANCAIRE", fichier.getTypeDetecte());
        assertEquals("FICHE_PAIE", fichier.getTypeDocument());         // le type déclaré n'est pas écrasé
        // la réponse complète de la classification est conservée pour le popup de l'agent
        assertNotNull(fichier.getClassificationJson());
        assertTrue(fichier.getClassificationJson().contains("\"controle\""));
        assertTrue(fichier.getClassificationJson().contains("RELEVE_BANCAIRE"));
        assertEquals(List.of("FICHE_PAIE → RELEVE_BANCAIRE"), resultat.get("typesEnConflit"));
        // le contenu est un relevé : l'extraction est faite comme pour un relevé
        assertEquals("RELEVE_BANCAIRE", corpsExtraction().get("type_document"));
    }

    @Test
    void typeConforme_aucunConflit_etLeTypeDeclareServAL_extraction() {
        fichier.setTypeDocument("FICHE_PAIE");
        ocrEtExtractionReussis();
        classification("FICHE_PAIE", true, true);

        Map resultat = service.verifierDossier(CIN_CLIENT, dossierId.toString());

        assertEquals(Boolean.FALSE, fichier.getTypeConflit());
        assertEquals(List.of(), resultat.get("typesEnConflit"));
        assertEquals("FICHE_PAIE", corpsExtraction().get("type_document"));
    }

    @Test
    void verdictIncertain_neSignaleAucunConflit() {
        fichier.setTypeDocument("FICHE_PAIE");
        ocrEtExtractionReussis();
        classification("RELEVE_BANCAIRE", false, null);

        Map resultat = service.verifierDossier(CIN_CLIENT, dossierId.toString());

        assertNull(fichier.getTypeConflit());
        assertEquals(List.of(), resultat.get("typesEnConflit"));
        assertEquals("FICHE_PAIE", corpsExtraction().get("type_document"));   // on garde le type déclaré
    }

    @Test
    void typeNonDeclare_prendLeTypeDetecteQuandIlEstFiable() {
        fichier.setTypeDocument("AUTRE");
        ocrEtExtractionReussis();
        classification("ATTESTATION_EMPLOI", true, null);

        service.verifierDossier(CIN_CLIENT, dossierId.toString());

        assertEquals("ATTESTATION_EMPLOI", fichier.getTypeDocument());
        assertNull(fichier.getTypeConflit());   // rien à comparer : pas de conflit
        assertEquals("ATTESTATION_EMPLOI", corpsExtraction().get("type_document"));
    }

    @Test
    void classificationEnPanne_neBloquePasLeDocument() {
        fichier.setTypeDocument("FICHE_PAIE");
        ocrEtExtractionReussis();
        when(restTemplate.postForObject(contains("/ai/classify-hybrid"), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("service IA indisponible"));

        service.verifierDossier(CIN_CLIENT, dossierId.toString());

        assertNull(fichier.getTypeConflit());
        // le document est quand même extrait, avec le type déclaré
        assertEquals("FICHE_PAIE", corpsExtraction().get("type_document"));
        verify(jsonExtractionRepository, times(1)).save(any());
    }

    // ── Garde « type de document contredit » ─────────────────────────────────

    private void typeEnConflit() {
        fichier.setTypeDocument("FICHE_PAIE");
        fichier.setTypeDetecte("RELEVE_BANCAIRE");
        fichier.setTypeConflit(true);
    }

    @Test
    void typeEnConflitSansConfirmation_bloqueEtNeCalculePasDeScore() {
        typeEnConflit();
        documentDejaVerifie(CIN_CLIENT);   // le CIN, lui, est cohérent

        Map resultat = service.analyserEtScorer(CIN_CLIENT, dossierId.toString(), false);

        assertEquals("TYPE_INCOHERENT", resultat.get("bloque"));
        assertEquals(List.of("FICHE_PAIE → RELEVE_BANCAIRE"), resultat.get("typesEnConflit"));
        assertEquals(List.of(), resultat.get("typesIncoherents"));
        verifierScoringAppele(0);
    }

    @Test
    void cinEtTypeEnConflit_lesDeuxSontSignales() {
        typeEnConflit();
        documentDejaVerifie("22222222");

        Map resultat = service.analyserEtScorer(CIN_CLIENT, dossierId.toString(), false);

        assertEquals("CIN_INCOHERENT", resultat.get("bloque"));
        assertEquals(List.of("FICHE_PAIE"), resultat.get("typesIncoherents"));
        assertEquals(List.of("FICHE_PAIE → RELEVE_BANCAIRE"), resultat.get("typesEnConflit"));
        verifierScoringAppele(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void typeEnConflitAvecConfirmation_poursuit_etLeScoringVoitLeVraiType() {
        typeEnConflit();
        documentDejaVerifie(CIN_CLIENT);
        reponseScoring();

        Map resultat = service.analyserEtScorer(CIN_CLIENT, dossierId.toString(), true);

        assertNull(resultat.get("bloque"));
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(contains("/ai/score/consommation"), captor.capture(), eq(Map.class));
        String texte = String.valueOf(((Map<String, Object>) captor.getValue().getBody()).get("document_text"));
        assertTrue(texte.contains("=== RELEVE_BANCAIRE ("), texte);   // l'IA voit un relevé, pas une fiche de paie
        assertFalse(texte.contains("=== FICHE_PAIE ("));
    }

    @Test
    void typeConforme_n_empecheJamaisLAnalyse() {
        fichier.setTypeDocument("FICHE_PAIE");
        fichier.setTypeDetecte("FICHE_PAIE");
        fichier.setTypeConflit(false);
        documentDejaVerifie(CIN_CLIENT);
        reponseScoring();

        Map resultat = service.analyserEtScorer(CIN_CLIENT, dossierId.toString(), false);

        assertNull(resultat.get("bloque"));
        verifierScoringAppele(1);
    }
}
