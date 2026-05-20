package com.example.crediSense.Service.impl;
import com.example.crediSense.Service.OcrResultService;
import com.example.crediSense.dto.request.OcrResultRequest;
import com.example.crediSense.dto.response.OcrResultResponse;
import com.example.crediSense.entity.Fichier;
import com.example.crediSense.repository.FichierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestre le pipeline ETL complet pour un fichier :
 *
 *   EXTRACT  → Doctr OCR (images/scans) ou PDFBox (PDF natifs)
 *   TRANSFORM → TextCleaningService (nettoyage)
 *   LOAD      → OcrResultService.create() (sauvegarde en base)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtlService {

    private final FichierRepository    fichierRepository;
    private final Doctrclientservice   doctrClientService;
    private final Textcleaningservice  textCleaningService;
    private final OcrResultService ocrResultService;

    // Types qui nécessitent OCR (images converties en PDF)
    private static final Set<String> TYPES_IMAGE = Set.of("jpg", "jpeg", "png");

    // ─── Point d'entrée principal ─────────────────────────────────────────────

    /**
     * Lance le pipeline ETL pour un fichier donné.
     * Appelé automatiquement après l'upload et la conversion PDF.
     *
     * @param fichierId UUID du Fichier en base
     * @return OcrResultResponse sauvegardé en base
     */
    public OcrResultResponse lancerEtl(UUID fichierId) {
        log.info("Démarrage ETL pour fichierId={}", fichierId);

        // Récupérer le fichier en base
        Fichier fichier = fichierRepository.findById(fichierId)
                .orElseThrow(() -> new RuntimeException("Fichier introuvable : " + fichierId));

        Path pdfPath = Paths.get(fichier.getCheminPdf());

        if (!Files.exists(pdfPath)) {
            throw new RuntimeException("PDF introuvable sur le disque : " + pdfPath);
        }

        String typeOriginal = fichier.getTypeOriginal().toLowerCase();

        // ── EXTRACT ──────────────────────────────────────────────────────────
        String texteBrut = extraire(pdfPath, typeOriginal);

        // ── TRANSFORM ────────────────────────────────────────────────────────
        String texteNettoye = textCleaningService.nettoyer(texteBrut);

        // ── LOAD ─────────────────────────────────────────────────────────────
        OcrResultRequest request = new OcrResultRequest();
        request.setFichierId(fichierId);
        request.setTexteBrut(texteBrut);
        request.setTexteNettoye(texteNettoye);
        request.setStatut("SUCCESS");

        OcrResultResponse result = ocrResultService.create(request);
        log.info("ETL terminé — OcrResult id={}", result.getId());

        return result;
    }

    // ─── EXTRACT : choix de la stratégie selon le type ───────────────────────

    private String extraire(Path pdfPath, String typeOriginal) {
        if (TYPES_IMAGE.contains(typeOriginal)) {
            // Image scannée → OCR via Doctr Python
            log.info("Stratégie : Doctr OCR (type={})", typeOriginal);
            Doctrclientservice.DoctrResponse doctrResponse = doctrClientService.callOcr(pdfPath);
            return doctrResponse.texte;
        } else {
            // PDF natif (converti depuis DOCX) → extraction directe PDFBox
            log.info("Stratégie : PDFBox extraction directe (type={})", typeOriginal);
            return extrairePdfNatif(pdfPath);
        }
    }

    // ─── PDFBox : extraction texte PDF natif ──────────────────────────────────

    private String extrairePdfNatif(Path pdfPath) {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String texte = stripper.getText(document);
            log.info("PDFBox extraction : {} caractères extraits", texte.length());
            return texte;
        } catch (Exception e) {
            log.error("Erreur PDFBox : {}", e.getMessage());
            throw new RuntimeException("Erreur extraction PDF natif : " + e.getMessage(), e);
        }
    }
}