package com.example.crediSense.Service.impl;

import com.example.crediSense.Service.OcrResultService;
import com.example.crediSense.dto.request.OcrResultRequest;
import com.example.crediSense.dto.response.OcrResultResponse;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.repository.FichierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EtlService {

    private final FichierRepository   fichierRepository;
    private final Textcleaningservice textCleaningService;
    private final OcrResultService    ocrResultService;
    private final Doctrclientservice  doctrClientService;
    private final GroqService         groqService;
    private final NlpClientService    nlpClientService;   // ← AJOUT

    private static final Set<String> IMAGE_TYPES = Set.of("jpg", "jpeg", "png");

    // ─── Point d'entrée principal ─────────────────────────────────────────────

    public OcrResultResponse lancerEtl(UUID fichierId) {
        log.info("=== Démarrage ETL — fichierId={} ===", fichierId);

        Fichier fichier = fichierRepository.findById(fichierId)
                .orElseThrow(() -> new RuntimeException("Fichier introuvable : " + fichierId));

        String typeOriginal = fichier.getTypeOriginal().toLowerCase().trim();
        Path   pdfPath      = Paths.get(fichier.getCheminPdf());

        if (!Files.exists(pdfPath)) {
            throw new RuntimeException("PDF introuvable sur le disque : " + pdfPath);
        }

        // ── EXTRACT ──────────────────────────────────────────────────────────
        String texteBrut = extraire(pdfPath, typeOriginal);
        log.info("EXTRACT terminé — {} caractères extraits", texteBrut.length());

        // ── TRANSFORM ────────────────────────────────────────────────────────
        String texteNettoye = textCleaningService.nettoyer(texteBrut);
        log.info("TRANSFORM terminé — {} caractères après nettoyage", texteNettoye.length());

        // ── LOAD — OcrResult ─────────────────────────────────────────────────
        OcrResultRequest request = new OcrResultRequest();
        request.setFichierId(fichierId);
        request.setTexteBrut(texteBrut);
        request.setTexteNettoye(texteNettoye);
        request.setStatut("SUCCESS");

        OcrResultResponse result = ocrResultService.create(request);
        log.info("LOAD OcrResult terminé — id={}", result.getId());

        // ── NLP — Classification du document ─────────────────────────────────
        try {
            String typeDocument = nlpClientService.classifierDocument(texteNettoye);
            fichier.setTypeDocument(typeDocument);
            fichierRepository.save(fichier);
            log.info("NLP Classification → typeDocument={}", typeDocument);
        } catch (Exception e) {
            log.error("Erreur NLP (non bloquante) : {}", e.getMessage());
        }

        // ── GROQ — Extraction JSON ────────────────────────────────────────────
        try {
            groqService.extraireJson(fichierId);
            log.info("GROQ extraction terminée pour fichierId={}", fichierId);
        } catch (Exception e) {
            log.error("Erreur GROQ (non bloquante) : {}", e.getMessage());
        }

        return result;
    }

    // ─── EXTRACT ─────────────────────────────────────────────────────────────

    private String extraire(Path pdfPath, String typeOriginal) {
        if ("docx".equals(typeOriginal)) {
            log.info("Stratégie CAS 1 : PDFBox (DOCX natif)");
            return extraireAvecPdfBox(pdfPath);
        } else if (IMAGE_TYPES.contains(typeOriginal)) {
            log.info("Stratégie CAS 2 : Doctr OCR (image) — type={}", typeOriginal);
            return extraireAvecDoctr(pdfPath);
        } else if ("pdf".equals(typeOriginal)) {
            log.info("Stratégie CAS 3 : détection automatique PDF");
            return extrairePdfAvecDetection(pdfPath);
        } else {
            throw new IllegalArgumentException("Type non supporté : " + typeOriginal);
        }
    }

    // ─── CAS 1 : PDFBox ───────────────────────────────────────────────────────

    private String extraireAvecPdfBox(Path pdfPath) {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String texte = stripper.getText(document);
            log.info("PDFBox — {} pages, {} caractères extraits",
                    document.getNumberOfPages(), texte.length());
            return texte;
        } catch (Exception e) {
            log.error("Erreur PDFBox : {}", e.getMessage());
            throw new RuntimeException("Erreur extraction PDF natif : " + e.getMessage(), e);
        }
    }

    // ─── CAS 2 : Doctr OCR ───────────────────────────────────────────────────

    private String extraireAvecDoctr(Path pdfPath) {
        log.info("Envoi vers Doctr OCR : {}", pdfPath.getFileName());
        Doctrclientservice.DoctrResponse response = doctrClientService.callOcr(pdfPath);
        if (response.texte == null || response.texte.isBlank()) {
            log.warn("Doctr a retourné un texte vide pour : {}", pdfPath.getFileName());
            return "";
        }
        log.info("Doctr OCR — {} pages, confidence={}, {} caractères",
                response.nbPages, response.confidence, response.texte.length());
        return response.texte;
    }

    // ─── CAS 3 : PDF — détection automatique ─────────────────────────────────

    private String extrairePdfAvecDetection(Path pdfPath) {
        String texte = extraireAvecPdfBox(pdfPath);
        if (texte.trim().length() < 50) {
            log.info("Texte PDFBox trop court ({} chars) → Doctr OCR", texte.trim().length());
            return extraireAvecDoctr(pdfPath);
        }
        log.info("PDF natif — {} caractères extraits via PDFBox", texte.length());
        return texte;
    }
}